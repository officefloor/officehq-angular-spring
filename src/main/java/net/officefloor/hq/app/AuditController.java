package net.officefloor.hq.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    public AuditController(Audit audit) {
        this.audit = audit;
    }

    /** The sheet's audit lines, newest first; with a name, only those about that person. */
    @GetMapping("/api/sheets/{sheetId}/audit")
    public List<String> lines(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestParam(required = false) @Size(max = 200) String name) {
        return audit.lines(sheetId, name);
    }
}
