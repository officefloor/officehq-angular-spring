package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's per-grade due-totals, which the screen compares with its own summary. */
@RestController
@Validated
public class CompareController {

    /** Whether the sheet exists, and per grade the due-totals of its people on it today (two decimals). */
    public record GradeDueTotalsView(boolean exists, Map<String, String> gradeDueTotals) {
    }

    private final RollupService service;

    public CompareController(RollupService service) {
        this.service = service;
    }

    @GetMapping("/api/sheets/{sheetId}/grade-due-totals")
    public GradeDueTotalsView gradeDueTotals(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        Map<String, String> grades = new TreeMap<>();
        boolean exists = service.sheetExists(sheetId);
        service.gradeDueTotals(sheetId).forEach((g, d) -> grades.put(g, d.toPlainString()));
        return new GradeDueTotalsView(exists, grades);
    }
}
