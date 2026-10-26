package net.officefloor.hq.app.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.Audit;
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

    public AuditController(Audit audit) {
        this.audit = audit;
    }

    /** The sheet's audit lines, newest first; with a name, only those about that person. */
    @GetMapping
    public List<String> list(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        return audit.lines(sheetId, name);
    }
}
