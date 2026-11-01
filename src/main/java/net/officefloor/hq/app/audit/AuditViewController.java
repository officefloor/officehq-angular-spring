package net.officefloor.hq.app.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/audit")
public class AuditViewController {

    private final AuditViewService service;

    public AuditViewController(AuditViewService service) {
        this.service = service;
    }

    /** The sheet's audit lines, newest first; with a name only those about that person, then its descendants' ones. */
    @GetMapping
    public List<String> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestParam(name = "name", required = false, defaultValue = "") @Size(max = 200) String name) {
        return service.lines(sheetId, name);
    }
}
