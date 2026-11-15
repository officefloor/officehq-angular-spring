package net.officefloor.hq.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit viewer: reads what the audit file holds for a sheet; it is not a second record. */
@RestController
@RequestMapping("/api/sheets")
@Validated
public class AuditController {

    private final Audit audit;

    public AuditController(Audit audit) {
        this.audit = audit;
    }

    /** The sheet's audit lines, newest first; with a name, only those about that person. */
    @GetMapping("/{id}/audit")
    public List<String> lines(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        return audit.lines(id, name);
    }
}
