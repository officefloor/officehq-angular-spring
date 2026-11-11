package net.officefloor.hq.app.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Showing a sheet's audit lines, optionally only those about one person, or those about one claim. */
@RestController
@RequestMapping("/api/audit")
public class AuditViewController {

    public record AuditViewRequest(@NotBlank @Size(max = 100) String sheet, @Size(max = 100) String name) {
    }

    public record ClaimTimelineRequest(@NotBlank @Size(max = 100) String sheet, @NotNull Long id) {
    }

    public record AuditViewResponse(List<String> lines) {
    }

    private final AuditViewService service;

    public AuditViewController(AuditViewService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public AuditViewResponse list(@Valid @RequestBody AuditViewRequest req) {
        return new AuditViewResponse(service.lines(req.sheet().trim(), req.name()));
    }

    public record ActivityRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    /** The sheet's activity: one line per user and event with its count, by user then event. */
    @PostMapping("/activity")
    public AuditViewResponse activity(@Valid @RequestBody ActivityRequest req) {
        return new AuditViewResponse(service.activity(req.sheet().trim()));
    }

    /** A claim's timeline: the audit lines about it, newest first. */
    @PostMapping("/claim")
    public AuditViewResponse claim(@Valid @RequestBody ClaimTimelineRequest req) {
        return new AuditViewResponse(service.claimLines(req.sheet().trim(), req.id()));
    }
}
