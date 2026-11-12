package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The figures of a sheet's descendants, which the screen adds to the sheet's own for its consolidated
 *  figures. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/descendants")
public class ConsolidatedController {

    /** The descendants' people count, and their due- and overdue-totals (two decimals), added up. */
    public record ConsolidatedView(int peopleCount, String dueTotal, String overdueTotal) {
    }

    private final Rollup rollup;

    public ConsolidatedController(Rollup rollup) {
        this.rollup = rollup;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ConsolidatedView figures(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        Rollup.Consolidated c = rollup.descendants(sheetId.trim());
        return new ConsolidatedView(c.peopleCount(), c.dueTotal().toPlainString(), c.overdueTotal().toPlainString());
    }
}
