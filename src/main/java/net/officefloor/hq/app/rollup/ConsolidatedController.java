package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public ConsolidatedController(SheetRepository sheets, Rollup rollup) {
        this.sheets = sheets;
        this.rollup = rollup;
    }

    /** With {@code self}, the sheet's own figures as the store holds them now are added in too: the screen asks for
     *  them so while it shows the sheet as of a date, as the consolidated figures are not dated. */
    @GetMapping("/figures")
    @Transactional(readOnly = true)
    public DescendantFigures figures(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestParam(defaultValue = "false") boolean self) {
        int people = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        List<String> counted = new ArrayList<>(sheets.descendantsOf(sheetId));
        if (self) {
            counted.add(0, sheetId);
        }
        for (String descendant : counted) {
            Rollup.Figures figures = rollup.of(descendant);
            people += figures.people().size();
            due = due.add(new BigDecimal(figures.dueTotal()));
            overdue = overdue.add(new BigDecimal(figures.overdueTotal()));
        }
        return new DescendantFigures(people, due.toPlainString(), overdue.toPlainString());
    }
}
