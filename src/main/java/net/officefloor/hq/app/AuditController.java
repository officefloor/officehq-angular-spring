package net.officefloor.hq.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    public AuditController(Audit audit) {
        this.audit = audit;
    }

    /** The sheet's audit lines, newest first; with a name, only those about that person. */
    @GetMapping("/api/sheets/{sheetId}/audit")
    public List<String> lines(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        return audit.lines(sheetId.trim(), name);
    }
}
