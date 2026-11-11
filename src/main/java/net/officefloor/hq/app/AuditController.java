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

    public AuditController(Audit audit, PersonRepository people) {
        this.audit = audit;
        this.people = people;
    }

    /**
     * The sheet's audit lines, newest first; with a name, only those about that person, followed, when that person was
     * transferred here, by the lines about them from the sheet they came from, newest first.
     */
    @GetMapping("/api/sheets/{sheetId}/audit")
    public List<String> lines(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        String sheet = sheetId.trim();
        List<String> lines = new ArrayList<>(audit.lines(sheet, name));
        if (!name.isBlank()) {
            people.findBySheetIdAndNameKey(sheet, Person.key(name))
                    .map(Person::getOriginSheet)
                    .ifPresent(origin -> lines.addAll(audit.lines(origin, name)));
        }
        return lines;
    }
}
