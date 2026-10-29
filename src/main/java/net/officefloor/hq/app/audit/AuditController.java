package net.officefloor.hq.app.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.TreeSet;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit viewer: reads what the audit file holds for a sheet; it writes nothing. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/audit")
public class AuditController {

    private final Audit audit;
    private final PersonRepository people;
    private final SheetRepository sheets;

    public AuditController(Audit audit, PersonRepository people, SheetRepository sheets) {
        this.audit = audit;
        this.people = people;
        this.sheets = sheets;
    }

    /**
     * The sheet's audit lines, newest first; with a name, only those about that person, followed by those about them
     * on each of the sheet's descendants (sheets by id, each newest first), then, when the person of that name on the sheet was transferred to it, by the lines about them from the sheet they came from, newest first.
     */
    @GetMapping
    public List<String> list(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        List<String> lines = new ArrayList<>(audit.lines(sheetId, name));
        String key = name.strip();
        if (!key.isEmpty()) {
            for (String descendant : descendants(sheetId)) {
                lines.addAll(audit.lines(descendant, key));
            }
            people.findBySheetIdAndNameIgnoreCase(sheetId, key).map(Person::getOriginSheet)
                    .ifPresent(origin -> lines.addAll(audit.lines(origin, key)));
        }
        return lines;
    }

    /** The sheet's activity: one line "<user> <EVENT> <count>" per user and event, sorted by user then event. */
    @GetMapping("/activity")
    public List<String> activity(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return audit.activity(sheetId);
    }

    /** The sheet's descendants, by id: the sheets naming it as parent, the ones naming those, and so on. */
    private TreeSet<String> descendants(String sheetId) {
        TreeSet<String> found = new TreeSet<>();
        Deque<String> toVisit = new ArrayDeque<>(List.of(sheetId));
        while (!toVisit.isEmpty()) {
            for (Sheet child : sheets.findByParentIdOrderByIdAsc(toVisit.poll())) {
                if (!child.getId().equals(sheetId) && found.add(child.getId())) {
                    toVisit.add(child.getId());
                }
            }
        }
        return found;
    }
}
