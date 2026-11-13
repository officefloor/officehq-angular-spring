package net.officefloor.hq.app.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's audit lines, as the audit file holds them. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/audit")
public class AuditController {

    private final AuditViewService service;

    public AuditController(AuditViewService service) {
        this.service = service;
    }

    /** The sheet's audit lines, newest first; with a name, only those about that person. */
    @GetMapping
    public List<String> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        return service.lines(sheetId.trim(), name);
    }

    /** A claim's timeline: the audit lines about it, newest first. */
    @GetMapping("/claims/{claimId}")
    public List<String> claim(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return service.claimLines(sheetId.trim(), claimId);
    }
}
