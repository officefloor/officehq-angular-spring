package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
public class ConsolidationController {

    /**
     * What the screen is told about the consolidated figures: the due amounts with two decimals, per grade
     * found the due-totals, and per status found the count of claims.
     */
    public record ConsolidatedView(int peopleCount, String dueTotal, String overdueTotal,
            Map<String, String> gradeDueTotals, Map<String, Integer> statusCounts) {
    }

    /**
     * A sheet's own summary due-total per grade, with two decimals (a grade nobody has left out); not
     * found when no sheet has the id.
     */
    public record GradeDueTotalsView(boolean found, Map<String, String> gradeDueTotals) {
    }

    private final Consolidation consolidation;

    public ConsolidationController(Consolidation consolidation) {
        this.consolidation = consolidation;
    }

    /**
     * The sheet's figures over it and all its descendants, as each stands now, or while an as-of date is
     * set, as each stood at the end of that date, or while a preview date is set, as each stands now read
     * as if its today were that date.
     */
    @GetMapping("/{id}/consolidated")
    public ConsolidatedView consolidated(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) String asOf,
            @RequestHeader(value = PreviewDate.HEADER, required = false) String preview) {
        Consolidation.Consolidated figures = consolidation.of(id, AsOfGuard.date(asOf),
                PreviewDate.date(asOf, preview));
        Map<String, String> grades = new LinkedHashMap<>();
        figures.gradeDueTotals().forEach((grade, due) -> grades.put(grade, due.toPlainString()));
        return new ConsolidatedView(figures.peopleCount(), figures.dueTotal().toPlainString(),
                figures.overdueTotal().toPlainString(), grades, figures.statusCounts());
    }

    /** A sheet's own summary due-totals per grade as it stands now, to compare another sheet with it. */
    @GetMapping("/{id}/grade-due-totals")
    public GradeDueTotalsView gradeDueTotals(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id) {
        return consolidation.gradeDueTotals(id)
                .map(totals -> {
                    Map<String, String> grades = new LinkedHashMap<>();
                    totals.forEach((grade, due) -> grades.put(grade, due.toPlainString()));
                    return new GradeDueTotalsView(true, grades);
                })
                .orElseGet(() -> new GradeDueTotalsView(false, Map.of()));
    }
}
