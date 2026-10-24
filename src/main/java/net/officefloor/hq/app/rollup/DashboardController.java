package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import net.officefloor.hq.app.history.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's steward dashboard over a sheet (see {@link DashboardService}). The signed-in
 * user's name travels in the {@code X-User} header. */
@RestController
@Validated
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    /** The dashboard as the sheet stands, or as it stood at the end of the as-of date when one is given. */
    @GetMapping("/api/sheets/{sheetId}/dashboard")
    public DashboardService.Counts dashboard(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return asOf == null ? service.live(sheetId.trim(), user.trim())
                : service.asOf(sheetId.trim(), user.trim(), asOf);
    }
}
