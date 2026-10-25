package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.time.LocalDate;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
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
 * due-total and overdue-total as that sheet shows it; per grade, the due-totals of the people on it on
 * their sheet's today (retired people left out, a due-total of n/a skipped); and per status, the number of
 * claims in it. The screen adds the sheet's own figures to give the consolidated figures.
 */
@Service
public class ConsolidatedService {

    /** The grades a due-total is added up for. */
    private static final List<String> GRADES = List.of("A", "B", "C");

    /** The statuses claims are counted by. */
    private static final List<ClaimStatus> STATUSES = List.of(ClaimStatus.DRAFT, ClaimStatus.OPEN,
            ClaimStatus.PENDING, ClaimStatus.SETTLED, ClaimStatus.VOID, ClaimStatus.REVIEW);

    /** The descendants' figures added up, with two decimals; all zero when the sheet has no children.
     * {@code gradeDueTotals} by grade, {@code statusCounts} by status. */
    public record Figures(int peopleCount, String dueTotal, String overdueTotal, Map<String, String> gradeDueTotals,
            Map<String, Integer> statusCounts) {
    }

    /** Figures being added up over sheets. */
    private static final class Sum {
        private int count;
        private BigDecimal due = BigDecimal.ZERO.setScale(2);
        private BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        private final Map<String, BigDecimal> grades = new LinkedHashMap<>();
        private final Map<String, Integer> statuses = new LinkedHashMap<>();

        Sum() {
            GRADES.forEach(g -> grades.put(g, BigDecimal.ZERO.setScale(2)));
            STATUSES.forEach(s -> statuses.put(s.name(), 0));
        }

        void add(RollupService.Snapshot snapshot, List<Person> everyone, List<Claim> claims, LocalDate today) {
            count += snapshot.people().size();
            due = due.add(new BigDecimal(snapshot.dueTotal()));
            overdue = overdue.add(new BigDecimal(snapshot.overdueTotal()));
            for (Person person : everyone) {
                if (person.isRetired()) continue;
                RollupService.Figures figures = snapshot.people().get(person.getId());
                if (figures == null || RollupService.NONE.equals(figures.dueTotal())) continue;
                grades.computeIfPresent(person.gradeOn(today), (g, sum) -> sum.add(new BigDecimal(figures.dueTotal())));
            }
            for (Claim claim : claims) {
                statuses.computeIfPresent(claim.getStatus().name(), (s, n) -> n + 1);
            }
        }

        Figures figures() {
            Map<String, String> gradeTotals = new LinkedHashMap<>();
            grades.forEach((g, sum) -> gradeTotals.put(g, sum.toPlainString()));
            return new Figures(count, due.toPlainString(), overdue.toPlainString(), gradeTotals, Map.copyOf(statuses));
        }
    }

    private final SheetRepository sheets;
    private final RollupService rollup;
    private final PersonTree tree;
    private final PersonRepository people;
    private final SheetHistory history;
    private final ClaimRepository claims;

    public ConsolidatedService(SheetRepository sheets, RollupService rollup, PersonTree tree,
            PersonRepository people, SheetHistory history, ClaimRepository claims) {
        this.history = history;
        this.claims = claims;
        this.sheets = sheets;
        this.rollup = rollup;
        this.tree = tree;
        this.people = people;
    }

    /** One person found by {@link #search}: their sheet, name and due-total as that sheet shows it, and
     * the grade in force on that sheet's today. */
    public record Found(String sheet, String name, String dueTotal, String grade) {
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
            LocalDate today = sheets.findById(id).map(Sheet::getToday).orElse(null);
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(id)) {
                if (!matches(person.getName(), needle)
                        && person.getAliases().stream().noneMatch(a -> matches(a, needle))) continue;
                RollupService.Figures figures = snapshot.people().get(person.getId());
                found.add(new Found(id, person.getName(),
                        figures == null ? RollupService.NONE : figures.dueTotal(), person.gradeOn(today)));
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
        Sum sum = new Sum();
        addAsOf(sum, sheetId, date);
        while (!parents.isEmpty()) {
            for (String child : history.childrenAsOf(parents.poll(), date)) {
                if (!seen.add(child)) continue;
                parents.add(child);
                addAsOf(sum, child, date);
            }
        }
        return sum.figures();
    }

    private void addAsOf(Sum sum, String sheetId, LocalDate date) {
        SheetAsOf sheet = history.asOf(sheetId, date);
        sum.add(rollup.snapshot(sheet.people(), sheet.claims(), sheet), sheet.people(), sheet.claims(), sheet.today());
    }

    /** The figures of the sheet's descendants as they stand, added up. */
    @Transactional(readOnly = true)
    public Figures descendantsOf(String sheetId) {
        Set<String> seen = new HashSet<>();
        seen.add(sheetId);
        Deque<String> parents = new ArrayDeque<>();
        parents.add(sheetId);
        Sum sum = new Sum();
        while (!parents.isEmpty()) {
            for (Sheet child : sheets.findByParentIdOrderByIdAsc(parents.poll())) {
                if (!seen.add(child.getId())) continue;
                parents.add(child.getId());
                sum.add(rollup.snapshot(child.getId()), people.findBySheetIdOrderByPositionAscIdAsc(child.getId()),
                        claims.findBySheetIdOrderByListedDescIdDesc(child.getId()), child.getToday());
            }
        }
        return sum.figures();
    }
}
