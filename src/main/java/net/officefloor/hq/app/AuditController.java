package net.officefloor.hq.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    public AuditController(Audit audit) {
        this.audit = audit;
    }

    /** The sheet's audit lines newest first, only those about the named person when a name is given. */
    @GetMapping
    public List<String> lines(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(defaultValue = "") String name) {
        return audit.lines(sheetId.trim(), name);
    }
}
