package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.officefloor.hq.app.history.AsOfGuard;
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

    /** What the screen is told about the consolidated figures: the due amounts with two decimals. */
    public record ConsolidatedView(int peopleCount, String dueTotal, String overdueTotal) {
    }

    private final Consolidation consolidation;

    public ConsolidationController(Consolidation consolidation) {
        this.consolidation = consolidation;
    }

    /**
     * The sheet's figures over it and all its descendants, as each stands now, or while an as-of date is
     * set, as each stood at the end of that date.
     */
    @GetMapping("/{id}/consolidated")
    public ConsolidatedView consolidated(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) String asOf) {
        Consolidation.Consolidated figures = consolidation.of(id, AsOfGuard.date(asOf));
        return new ConsolidatedView(figures.peopleCount(), figures.dueTotal().toPlainString(),
                figures.overdueTotal().toPlainString());
    }
}
