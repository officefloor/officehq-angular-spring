package net.officefloor.hq.app.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
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

    public AuditController(Audit audit, PersonRepository people) {
        this.audit = audit;
        this.people = people;
    }

    /**
     * The sheet's audit lines, newest first; with a name, only those about that person, followed, when the person of
     * that name on the sheet was transferred to it, by the lines about them from the sheet they came from, newest first.
     */
    @GetMapping
    public List<String> list(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        List<String> lines = new ArrayList<>(audit.lines(sheetId, name));
        String key = name.strip();
        if (!key.isEmpty()) {
            people.findBySheetIdAndNameIgnoreCase(sheetId, key).map(Person::getOriginSheet)
                    .ifPresent(origin -> lines.addAll(audit.lines(origin, key)));
        }
        return lines;
    }
}
