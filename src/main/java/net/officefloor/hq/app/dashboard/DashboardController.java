package net.officefloor.hq.app.dashboard;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.officefloor.hq.app.SignedIn;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.PreviewDate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets")
@Validated
public class DashboardController {

    /** What the screen is told about the steward's dashboard: the counts, all null for a user who is not a steward. */
    public record DashboardView(Long pendingCount, Long reviewCount, Long overdueCount) {
    }

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    /**
     * The sheet's steward dashboard as it stands now, for the signed-in user; empty while an as-of date
     * is set, as the dashboard shows only the sheet as it stands now; its figures read as if the sheet's
     * today were the preview date while one is set.
     */
    @GetMapping("/{id}/dashboard")
    public DashboardView dashboard(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) String asOf,
            @RequestHeader(value = PreviewDate.HEADER, required = false) String preview) {
        if (AsOfGuard.date(asOf) != null) {
            return new DashboardView(null, null, null);
        }
        return service.of(id, SignedIn.name(user), PreviewDate.date(preview))
                .map(d -> new DashboardView(d.pendingCount(), d.reviewCount(), d.overdueCount()))
                .orElse(new DashboardView(null, null, null));
    }
}
