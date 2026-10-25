package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.officefloor.hq.app.sheet.PreviewDate;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet's descendants (its children — the sheets naming it as their parent — their children, and so
 * on) and their figures added up: the number of people on them (retired ones included), each person counted
 * once over the tree (people whose names or aliases match, as names are compared, are one person), and
 * each one's due-total and overdue-total as that sheet shows it, the single highest and lowest person's
 * figure over all of them left out when three or more have one; per grade, the due-totals of the people on it on
 * their sheet's today (retired people left out, a due-total of n/a skipped); and per status, the number of
 * claims in it. The screen adds the sheet's own people in, trimming once over the whole tree, to give the
 * consolidated figures.
 */
@Service
public class ConsolidatedService {

    /** The grades a due-total is added up for. */
    private static final List<String> GRADES = List.of("A", "B", "C");

    /** The statuses claims are counted by. */
    private static final List<ClaimStatus> STATUSES = List.of(ClaimStatus.DRAFT, ClaimStatus.OPEN,
            ClaimStatus.PENDING, ClaimStatus.SETTLED, ClaimStatus.VOID, ClaimStatus.REVIEW);

    /** The descendants' figures added up, with two decimals; all zero when the sheet has no children.
     * {@code peopleCount} counts each person once over them (people whose names or aliases match, as names
     * are compared, are one person); {@code dueTotal} and {@code overdueTotal} leave out the single highest
     * and lowest person's figure over all of them when three or more have one. {@code gradeDueTotals} by
     * grade, {@code statusCounts} by status. So that the screen can add the sheet's own people in, each
     * person's due-total ({@code dueFigures}, n/a ones left out), overdue-total ({@code overdueFigures}) and
     * name keys, their name's then their aliases' ({@code nameKeys}). */
    public record Figures(int peopleCount, String dueTotal, String overdueTotal, Map<String, String> gradeDueTotals,
            Map<String, Integer> statusCounts, List<String> dueFigures, List<String> overdueFigures,
            List<List<String>> nameKeys) {
    }

    /** Figures being added up over sheets. */
    private static final class Sum {
        private final List<BigDecimal> due = new ArrayList<>();
        private final List<BigDecimal> overdue = new ArrayList<>();
        private final List<List<String>> keys = new ArrayList<>();
        private final Map<String, BigDecimal> grades = new LinkedHashMap<>();
        private final Map<String, Integer> statuses = new LinkedHashMap<>();

        Sum() {
            GRADES.forEach(g -> grades.put(g, BigDecimal.ZERO.setScale(2)));
            STATUSES.forEach(s -> statuses.put(s.name(), 0));
        }

        void add(RollupService.Snapshot snapshot, List<Person> everyone, List<Claim> claims, LocalDate today) {
            for (Person person : everyone) {
                RollupService.Figures shown = snapshot.people().get(person.getId());
                if (shown == null) continue;
                if (!RollupService.NONE.equals(shown.dueTotal())) due.add(new BigDecimal(shown.dueTotal()));
                overdue.add(new BigDecimal(shown.overdueTotal()));
                List<String> personKeys = new ArrayList<>();
                personKeys.add(Person.keyOf(person.getName()));
                person.getAliases().forEach(a -> personKeys.add(Person.keyOf(a)));
                keys.add(personKeys);
            }
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
            return new Figures(distinctPeople(keys), RollupService.trimmedSum(due).toPlainString(),
                    RollupService.trimmedSum(overdue).toPlainString(), gradeTotals, Map.copyOf(statuses),
                    due.stream().map(BigDecimal::toPlainString).toList(),
                    overdue.stream().map(BigDecimal::toPlainString).toList(), List.copyOf(keys));
        }
    }

    /** How many people there are among those with the name keys given, people sharing a key (directly or
     * through others) being one person. */
    static int distinctPeople(List<List<String>> keys) {
        int[] group = new int[keys.size()];
        for (int i = 0; i < group.length; i++) group[i] = i;
        Map<String, Integer> firstWith = new HashMap<>();
        int count = keys.size();
        for (int i = 0; i < keys.size(); i++) {
            for (String key : keys.get(i)) {
                Integer other = firstWith.putIfAbsent(key, i);
                if (other == null) continue;
                int a = root(group, i);
                int b = root(group, other);
                if (a != b) {
                    group[a] = b;
                    count--;
                }
            }
        }
        return count;
    }

    private static int root(int[] group, int i) {
        while (group[i] != i) i = group[i] = group[group[i]];
        return i;
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

    /** A sheet's own due-totals per grade as its summary shows them: the due-totals of the people on the
     * grade on its today (retired people left out, a due-total of n/a skipped); {@code exists} false, and
     * no grades, when the sheet has never been loaded. */
    public record GradeDueTotals(boolean exists, Map<String, String> dueTotals) {
    }

    /** The sheet's own due-totals per grade, as they stand (see {@link GradeDueTotals}). */
    @Transactional(readOnly = true)
    public GradeDueTotals gradeDueTotalsOf(String sheetId) {
        return sheets.findById(sheetId).map(sheet -> {
            Sum sum = new Sum();
            sum.add(rollup.snapshot(sheet.getId()), people.findBySheetIdOrderByPositionAscIdAsc(sheet.getId()),
                    List.of(), PreviewDate.shown(sheet.getToday()));
            return new GradeDueTotals(true, sum.figures().gradeDueTotals());
        }).orElseGet(() -> new GradeDueTotals(false, Map.of()));
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
                        claims.findBySheetIdOrderByListedDescIdDesc(child.getId()), PreviewDate.shown(child.getToday()));
            }
        }
        return sum.figures();
    }
}
