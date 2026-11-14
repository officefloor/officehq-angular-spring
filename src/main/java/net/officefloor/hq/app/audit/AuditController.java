package net.officefloor.hq.app.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class AuditController {

    private final AuditViewService service;

    public AuditController(AuditViewService service) {
        this.service = service;
    }

    /** The sheet's audit lines as the file holds them, newest first; with a name, only those about that person. */
    @GetMapping("/audit")
    public List<String> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestParam(required = false) @Size(max = 200) String name) {
        return service.lines(sheetId.trim(), name);
    }

    /** The sheet's activity: one "<user> <EVENT> <count>" line per user and event, sorted by user then event. */
    @GetMapping("/activity")
    public List<String> activity(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.activity(sheetId.trim());
    }

    /** A claim's timeline: the audit lines about it as the file holds them, newest first. */
    @GetMapping("/claims/{id}/timeline")
    public List<String> timeline(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id) {
        return service.claimLines(sheetId.trim(), id);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }
}
