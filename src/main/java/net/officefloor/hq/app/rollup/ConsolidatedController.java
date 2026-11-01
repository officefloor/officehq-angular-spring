package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import net.officefloor.hq.app.sheet.SheetParentRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The figures of a sheet's descendants (the sheets naming it as their parent, theirs and so on),
 * added up: how many people they have, and each one's due-total and overdue-total as that sheet
 * shows them, and the sheet's own figures as it holds them now. The screen adds the sheet's own figures,
 * as shown (as it holds them now while it is shown as of a date), to make the consolidated ones.
 */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/descendants")
public class ConsolidatedController {

    /** The descendants' figures added up, then the sheet's own; the totals have two decimals. */
    public record DescendantsView(int sheets, int people, String dueTotal, String overdueTotal, int ownPeople,
            String ownDueTotal, String ownOverdueTotal) {}

    private final SheetParentRepository parents;
    private final RollupService rollup;

    public ConsolidatedController(SheetParentRepository parents, RollupService rollup) {
        this.parents = parents;
        this.rollup = rollup;
    }

    @GetMapping("/figures")
    @Transactional(readOnly = true)
    public DescendantsView figures(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        int sheets = 0;
        int people = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        for (String descendant : parents.descendantsOf(sheetId)) {
            RollupService.Snapshot figures = rollup.current(descendant);
            sheets++;
            people += figures.people().size();
            due = due.add(figures.dueTotal());
            overdue = overdue.add(figures.overdueTotal());
        }
        RollupService.Snapshot own = rollup.current(sheetId);
        return new DescendantsView(sheets, people, due.toPlainString(), overdue.toPlainString(), own.people().size(),
                own.dueTotal().setScale(2).toPlainString(), own.overdueTotal().setScale(2).toPlainString());
    }
}
