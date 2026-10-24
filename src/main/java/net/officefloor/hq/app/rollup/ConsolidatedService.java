package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.time.LocalDate;
import net.officefloor.hq.app.history.SheetAsOf;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonTree;
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
    private final PersonTree tree;
    private final PersonRepository people;
    private final SheetHistory history;

    public ConsolidatedService(SheetRepository sheets, RollupService rollup, PersonTree tree,
            PersonRepository people, SheetHistory history) {
        this.history = history;
        this.sheets = sheets;
        this.rollup = rollup;
        this.tree = tree;
        this.people = people;
    }

    /** One person found by {@link #search}: their sheet, name and due-total as that sheet shows it. */
    public record Found(String sheet, String name, String dueTotal) {
    }

    /** Every person of the sheet and the other sheets of its tree whose name or an alias contains
     * {@code text} (case ignored; blank finds everyone): the sheet's own people first in list order, then
     * the other sheets' by sheet id, each sheet's in its list order. */
    @Transactional(readOnly = true)
    public List<Found> search(String sheetId, String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<String> order = new ArrayList<>();
        order.add(sheetId);
        order.addAll(new TreeSet<>(tree.othersOf(sheetId)));
        List<Found> found = new ArrayList<>();
        for (String id : order) {
            RollupService.Snapshot snapshot = rollup.snapshot(id);
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(id)) {
                if (!matches(person.getName(), needle)
                        && person.getAliases().stream().noneMatch(a -> matches(a, needle))) continue;
                RollupService.Figures figures = snapshot.people().get(person.getId());
                found.add(new Found(id, person.getName(),
                        figures == null ? RollupService.NONE : figures.dueTotal()));
            }
        }
        return found;
    }

    private static boolean matches(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** The figures of the sheet itself and its descendants as they stood at the end of {@code date}, added
     * up: every sheet of the tree (its children as they named their parent then, their children, and so
     * on) read as it stood then, that date standing in for its today (see {@link SheetHistory#asOf}). The
     * consolidated figures the screen shows while it reads the sheet as it stood on that date. */
    @Transactional(readOnly = true)
    public Figures consolidatedAsOf(String sheetId, LocalDate date) {
        Set<String> seen = new HashSet<>();
        seen.add(sheetId);
        Deque<String> parents = new ArrayDeque<>();
        parents.add(sheetId);
        RollupService.Snapshot own = snapshotAsOf(sheetId, date);
        int count = own.people().size();
        BigDecimal due = new BigDecimal(own.dueTotal());
        BigDecimal overdue = new BigDecimal(own.overdueTotal());
        while (!parents.isEmpty()) {
            for (String child : history.childrenAsOf(parents.poll(), date)) {
                if (!seen.add(child)) continue;
                parents.add(child);
                RollupService.Snapshot snapshot = snapshotAsOf(child, date);
                count += snapshot.people().size();
                due = due.add(new BigDecimal(snapshot.dueTotal()));
                overdue = overdue.add(new BigDecimal(snapshot.overdueTotal()));
            }
        }
        return new Figures(count, due.toPlainString(), overdue.toPlainString());
    }

    private RollupService.Snapshot snapshotAsOf(String sheetId, LocalDate date) {
        SheetAsOf sheet = history.asOf(sheetId, date);
        return rollup.snapshot(sheet.people(), sheet.claims(), sheet);
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
