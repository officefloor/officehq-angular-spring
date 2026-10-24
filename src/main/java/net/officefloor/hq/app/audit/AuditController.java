package net.officefloor.hq.app.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.Audit;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit lines of a sheet, read from the audit file as it holds them, newest first. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/audit")
@Validated
public class AuditController {

    private final Audit audit;

    public AuditController(Audit audit) {
        this.audit = audit;
    }

    /** Every line of the sheet, or (with a name) only those about that person. */
    @GetMapping
    public List<String> lines(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        return audit.lines(sheetId.trim(), name);
    }
}
