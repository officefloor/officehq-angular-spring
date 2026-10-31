package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.history.SheetState;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's descendants' figures, which the screen adds to the sheet's own to show them consolidated. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/descendants")
public class ConsolidatedController {

    /** The figures of the sheet's descendants (its children, their children and so on; not the sheet itself) added
     *  up: how many people, and each one's due-total and overdue-total as that sheet shows it, with two decimals. */
    public record DescendantFigures(int peopleCount, String dueTotal, String overdueTotal) {
    }

    private final SheetRepository sheets;
    private final Rollup rollup;
    private final SheetHistory history;

    public ConsolidatedController(SheetRepository sheets, Rollup rollup, SheetHistory history) {
        this.sheets = sheets;
        this.rollup = rollup;
        this.history = history;
    }

    /** With {@code self}, the sheet's own figures are added in too. While the screen shows the sheet as of a date,
     *  each sheet of the tree (as it names its parent now) counts as it stood at the end of that date, that date
     *  standing in for its today; one with no change made on it by then has no one on it. */
    @GetMapping("/figures")
    @Transactional(readOnly = true)
    public DescendantFigures figures(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestParam(defaultValue = "false") boolean self,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        int people = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        List<String> counted = new ArrayList<>(sheets.descendantsOf(sheetId));
        if (self) {
            counted.add(0, sheetId);
        }
        for (String descendant : counted) {
            Rollup.Figures figures = asOf == null ? rollup.of(descendant) : figuresAsOf(descendant, asOf);
            if (figures == null) {
                continue;
            }
            people += figures.people().size();
            due = due.add(new BigDecimal(figures.dueTotal()));
            overdue = overdue.add(new BigDecimal(figures.overdueTotal()));
        }
        return new DescendantFigures(people, due.toPlainString(), overdue.toPlainString());
    }

    /** A sheet's figures as it stood at the end of a date, that date as its today; none when no change was made on it
     *  by then. */
    private Rollup.Figures figuresAsOf(String sheetId, LocalDate asOf) {
        Sheet live = sheets.findById(sheetId).orElse(null);
        SheetState then = live == null ? null : history.asOf(sheetId, asOf).orElse(null);
        if (then == null) {
            return null;
        }
        return rollup.of(then.rebuildSheet(live, asOf), history.ratesAsOf(sheetId, asOf),
                then.rebuildPeople(sheetId), then.rebuildClaims(sheetId));
    }
}
