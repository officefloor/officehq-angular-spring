package net.officefloor.hq.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit viewer: what the audit file holds for a sheet, read from the file itself. The signed-in user's name
 * travels in the {@code X-User} header.
 */
@RestController
@Validated
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
     * The sheet's audit lines, newest first; with a name, only those about that person, followed by the lines about them
     * on each of the sheet's descendants (sheets by id, each sheet's lines newest first) and then, when that person was
     * transferred here, by the lines about them from the sheet they came from, newest first.
     */
    @GetMapping("/api/sheets/{sheetId}/audit")
    public List<String> lines(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        String sheet = sheetId.trim();
        List<String> lines = new ArrayList<>(audit.lines(sheet, name));
        if (!name.isBlank()) {
            for (String descendant : descendants(sheet)) {
                lines.addAll(audit.lines(descendant, name));
            }
            people.findBySheetIdAndNameKey(sheet, Person.key(name))
                    .map(Person::getOriginSheet)
                    .ifPresent(origin -> lines.addAll(audit.lines(origin, name)));
        }
        return lines;
    }

    /** The ids of the sheet's descendants, each once, by id; a parent loop never brings back the sheet. */
    private List<String> descendants(String sheetId) {
        List<String> seen = new ArrayList<>(List.of(sheetId));
        for (int i = 0; i < seen.size(); i++) {
            for (String child : sheets.childIdsOf(seen.get(i))) {
                if (!seen.contains(child)) {
                    seen.add(child);
                }
            }
        }
        return seen.subList(1, seen.size()).stream().sorted().toList();
    }
}
