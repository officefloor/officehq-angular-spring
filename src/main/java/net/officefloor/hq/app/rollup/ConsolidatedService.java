package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.setting.SheetParentLink;
import net.officefloor.hq.app.setting.SheetParentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures of a sheet's descendants (its children, the sheets naming it as their parent, then
 * theirs, and so on), added up: the number of people on them and each one's due-total and
 * overdue-total as that sheet shows it. The screen adds the sheet's own figures to make its
 * consolidated figures. A sheet with no children has none.
 */
@Service
public class ConsolidatedService {

    /** The descendants, in the order found (nearest first), and their figures added up; the totals with two
     *  decimals. */
    public record Descendants(List<String> sheets, int peopleCount, String dueTotal, String overdueTotal) {
    }

    private final SheetParentRepository parents;
    private final Rollup rollup;

    public ConsolidatedService(SheetParentRepository parents, Rollup rollup) {
        this.parents = parents;
        this.rollup = rollup;
    }

    @Transactional(readOnly = true)
    public Descendants descendantsOf(String sheetId) {
        Set<String> found = new LinkedHashSet<>();
        Deque<String> next = new ArrayDeque<>(List.of(sheetId));
        while (!next.isEmpty()) {
            for (SheetParentLink child : parents.findByParentId(next.pop())) {
                if (!child.getSheetId().equals(sheetId) && found.add(child.getSheetId())) next.add(child.getSheetId());
            }
        }
        int peopleCount = 0;
        BigDecimal due = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        for (String sheet : found) {
            Rollup.Snapshot figures = rollup.snapshot(sheet);
            peopleCount += figures.people().size();
            due = due.add(figures.dueTotal());
            overdue = overdue.add(figures.overdueTotal());
        }
        return new Descendants(new ArrayList<>(found), peopleCount, text(due), text(overdue));
    }

    private static String text(BigDecimal figure) {
        return figure.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
