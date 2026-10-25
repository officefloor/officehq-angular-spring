package net.officefloor.hq.app.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    /** Every line of the sheet, or (with a name) only those about that person,
     * then those about them on its descendants (sheets by id), then those about them from the sheet they
     * were transferred from. */
    @GetMapping
    public List<String> lines(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        return audit.lines(sheetId.trim(), name);
    }

    /** The sheet's activity: one line per user and event with its count, by user then event. */
    @GetMapping("/activity")
    public List<String> activity(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return audit.activity(sheetId.trim());
    }

    /** The claim's timeline: the lines about it, newest first. */
    @GetMapping("/claims/{claimId}")
    public List<String> claimLines(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId) {
        return audit.claimLines(sheetId.trim(), claimId);
    }
}
