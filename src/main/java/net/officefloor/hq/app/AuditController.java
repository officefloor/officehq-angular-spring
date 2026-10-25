package net.officefloor.hq.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
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

    public AuditController(Audit audit, PersonRepository people) {
        this.audit = audit;
        this.people = people;
    }

    /**
     * The sheet's audit lines newest first, only those about the named person when a name is given. When the
     * person named was transferred here, their lines on this sheet are followed by the lines about them on the
     * sheet they came from, newest first, from the one recording their transfer here onwards (older lines).
     */
    @GetMapping
    public List<String> lines(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(defaultValue = "") String name) {
        String sheet = sheetId.trim();
        List<String> lines = audit.lines(sheet, name);
        if (name.isBlank()) return lines;
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
}
