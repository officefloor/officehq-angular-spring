package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet's descendants (its children — the sheets naming it as their parent — their children, and so
 * on) and their figures added up: the number of people on them (retired ones included) and each one's
 * due-total and overdue-total as that sheet shows it. The screen adds the sheet's own figures to give
 * the consolidated figures.
 */
@Service
public class ConsolidatedService {

    /** The descendants' figures added up, with two decimals; all zero when the sheet has no children. */
    public record Figures(int peopleCount, String dueTotal, String overdueTotal) {
    }

    private final SheetRepository sheets;
    private final RollupService rollup;

    public ConsolidatedService(SheetRepository sheets, RollupService rollup) {
        this.sheets = sheets;
        this.rollup = rollup;
    }

    /** The figures of the sheet's descendants as they stand, added up. */
    @Transactional(readOnly = true)
    public Figures descendantsOf(String sheetId) {
        Set<String> seen = new HashSet<>();
        seen.add(sheetId);
        Deque<String> parents = new ArrayDeque<>();
        parents.add(sheetId);
        int count = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        while (!parents.isEmpty()) {
            for (Sheet child : sheets.findByParentIdOrderByIdAsc(parents.poll())) {
                if (!seen.add(child.getId())) continue;
                parents.add(child.getId());
                RollupService.Snapshot snapshot = rollup.snapshot(child.getId());
                count += snapshot.people().size();
                due = due.add(new BigDecimal(snapshot.dueTotal()));
                overdue = overdue.add(new BigDecimal(snapshot.overdueTotal()));
            }
        }
        return new Figures(count, due.toPlainString(), overdue.toPlainString());
    }
}
