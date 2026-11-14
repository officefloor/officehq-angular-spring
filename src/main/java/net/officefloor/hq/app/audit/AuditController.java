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

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/audit")
public class AuditController {

    private final AuditViewService service;

    public AuditController(AuditViewService service) {
        this.service = service;
    }

    /** The sheet's audit lines as the file holds them, newest first; with a name, only those about that person. */
    @GetMapping
    public List<String> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestParam(required = false) @Size(max = 200) String name) {
        return service.lines(sheetId.trim(), name);
    }
}
