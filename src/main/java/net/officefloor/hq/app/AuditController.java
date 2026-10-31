package net.officefloor.hq.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit viewer: reads back what the audit file holds for a sheet; it keeps no record of its own. */
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

    /** The sheet's audit lines, newest first; with a name, only those about that person, followed by the lines about
     *  them on each of the sheet's descendants (sheets by id, each newest first), then (when the sheet's person of
     *  that name or alias was transferred here) by the lines about them on the sheet they came from. */
    @GetMapping("/api/sheets/{sheetId}/audit")
    public List<String> lines(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestParam(required = false) @Size(max = 200) String name) {
        List<String> lines = new ArrayList<>(audit.lines(sheetId, name));
        if (name != null && !name.isBlank()) {
            sheets.descendantsOf(sheetId).stream().sorted()
                    .forEach(descendant -> lines.addAll(audit.lines(descendant, name)));
            people.findBySheetIdAndNameOrAliasIgnoreCase(sheetId, name.trim())
                    .filter(p -> p.getOriginSheetId() != null)
                    .ifPresent(p -> lines.addAll(audit.lines(p.getOriginSheetId(), p.getName())));
        }
        return lines;
    }
}
