package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The figures of a sheet's descendants. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@Validated
public class ConsolidatedController {

    private final RollupService rollups;

    public ConsolidatedController(RollupService rollups) {
        this.rollups = rollups;
    }

    /** The people, due-total and overdue-total of the sheet's descendants added up, the sheet itself left out. */
    @GetMapping("/api/sheets/{sheetId}/descendants/figures")
    public RollupService.Consolidated descendants(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return rollups.descendantFigures(sheetId.trim());
    }
}
