package net.officefloor.hq.app.rollup;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * The people, due-total and overdue-total of the sheet and its descendants added up, as the store holds them now:
     * an as-of view of the sheet leaves them live.
     */
    @GetMapping("/api/sheets/{sheetId}/consolidated/figures")
    public RollupService.Consolidated consolidated(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return rollups.consolidatedFigures(sheetId.trim());
    }

    /**
     * The people on the sheet and the other sheets of its tree whose name or an alias contains the text (case ignored),
     * one line each: {@code <sheet id> <name> <due-total>}. It changes nothing.
     */
    @GetMapping("/api/sheets/{sheetId}/tree/search")
    public List<String> search(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return rollups.search(sheetId.trim(), name);
    }
}
