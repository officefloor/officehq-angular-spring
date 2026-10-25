package net.officefloor.hq.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.TreeSet;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.setting.SheetParentLink;
import net.officefloor.hq.app.setting.SheetParentRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit viewer: reads the sheet's records from the audit file; it keeps no record of its own. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/audit")
@Validated
public class AuditController {

    private final Audit audit;
    private final PersonRepository people;
    private final SheetParentRepository parents;

    public AuditController(Audit audit, PersonRepository people, SheetParentRepository parents) {
        this.audit = audit;
        this.people = people;
        this.parents = parents;
    }

    /**
     * The sheet's audit lines newest first, only those about the named person when a name is given. When the
     * person named was transferred here, their lines on this sheet are followed by the lines about them on the
     * sheet they came from, newest first, from the one recording their transfer here onwards (older lines).
     * When a name is given, the sheet's own lines are followed by those about the named person on each of the
     * sheet's descendants (its children, theirs, and so on), sheets by id, each newest first; before the lines
     * from the sheet the person came from.
     */
    @GetMapping
    public List<String> lines(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(defaultValue = "") String name) {
        String sheet = sheetId.trim();
        List<String> lines = audit.lines(sheet, name);
        if (name.isBlank()) return lines;
        lines = new ArrayList<>(lines);
        for (String descendant : descendantsOf(sheet)) {
            lines.addAll(audit.lines(descendant, name));
        }
        String origin = people.findBySheetIdAndNameKey(sheet, Person.keyOf(name))
                .map(Person::getOriginSheetId).orElse(null);
        if (origin == null) return lines;
        List<String> theirs = audit.lines(origin, name);
        String left = "PERSON_TRANSFERRED sheet=" + origin + " ";
        String to = " to=" + sheet;
        int from = 0;
        for (int i = 0; i < theirs.size(); i++) {
            String line = theirs.get(i);
            if (line.startsWith(left) && (line.endsWith(to) || line.contains(to + " "))) {
                from = i;
                break;
            }
        }
        List<String> all = new ArrayList<>(lines);
        all.addAll(theirs.subList(from, theirs.size()));
        return all;
    }

    /** The sheet's descendants: the sheets naming it as their parent, then theirs, and so on; by id. */
    private TreeSet<String> descendantsOf(String sheetId) {
        TreeSet<String> found = new TreeSet<>();
        Deque<String> next = new ArrayDeque<>(List.of(sheetId));
        while (!next.isEmpty()) {
            for (SheetParentLink child : parents.findByParentId(next.pop())) {
                if (!child.getSheetId().equals(sheetId) && found.add(child.getSheetId())) next.add(child.getSheetId());
            }
        }
        return found;
    }
}
