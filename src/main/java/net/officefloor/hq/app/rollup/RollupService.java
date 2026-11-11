package net.officefloor.hq.app.rollup;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.sheet.AsOfFilter;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetHistory;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: each person's claimed, due-total and overdue-total
 * (the figures of their overdue claims that count, not capped), and the sheet's totals of each. A change takes a {@link #snapshot} before it alters anything
 * and calls {@link #recordChanges} after its own audit lines, which appends one ROLLUP line per
 * person whose figures moved (people in the order they were added; a new person counts as moved),
 * then a TOTALS line when any of the sheet's totals moved. A hold (a person's or a claim's) takes nothing out
 * of any figure. A person whose retirement date the sheet's today has reached: their OPEN and PENDING claims count in
 * none of the figures, nor in a period's due-total; their SETTLED claims count in all. An overdue claim's figure has
 * its late charge added on top, and a live due is then never below the floor in force on its claim's date. A person's
 * due-total is at most the cap in force on the sheet's today, when there is one. With an alert threshold
 * set, the rollup lines are followed by one ALERT line per person (in list order) whose due-total went
 * from at or below it (or none) to above it.
 */
@Service
public class RollupService {

    /**
     * A person's figures: claimed, due-total (null for n/a) and overdue-total; with their grade on the
     * sheet's today and whether they are retired.
     */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal, String grade,
            boolean retired) {
    }

    /** The sheet's figures: each person's by id, in the order they were added. */
    public record Snapshot(Map<Long, Figures> people) {

        /** Per grade (on the sheet's today), the due-totals of the people not retired on it added up (n/a is no figure). */
        public Map<String, BigDecimal> gradeDueTotals() {
            Map<String, BigDecimal> totals = new LinkedHashMap<>();
            for (Figures figures : people.values()) {
                if (!figures.retired() && figures.grade() != null) {
                    totals.merge(figures.grade(),
                            figures.dueTotal() == null ? zero() : figures.dueTotal(), BigDecimal::add);
                }
            }
            return totals;
        }

        public BigDecimal claimedTotal() {
            return people.values().stream().map(Figures::claimed).reduce(zero(), BigDecimal::add);
        }

        /** People's due-totals added up (n/a is no figure), trimmed as {@link #trimmed} says. */
        public BigDecimal dueTotal() {
            return trimmed(people.values().stream().map(Figures::dueTotal).filter(Objects::nonNull).toList());
        }

        /** People's overdue-totals added up, trimmed as {@link #trimmed} says. */
        public BigDecimal overdueTotal() {
            return trimmed(people.values().stream().map(Figures::overdueTotal).toList());
        }

        /** The sum of people's figures, leaving out the single highest and lowest when there are three or more. */
        private static BigDecimal trimmed(List<BigDecimal> figures) {
            BigDecimal sum = figures.stream().reduce(zero(), BigDecimal::add);
            if (figures.size() < 3) {
                return sum;
            }
            return sum.subtract(figures.stream().reduce(BigDecimal::max).orElseThrow())
                    .subtract(figures.stream().reduce(BigDecimal::min).orElseThrow());
        }
    }

    /**
     * Figures added up over sheets: their people (retired included), due-totals and overdue-totals;
     * per grade the due-totals of the people on it (retired left out), and per status the count of claims in it.
     */
    public record Consolidated(int peopleCount, BigDecimal dueTotal, BigDecimal overdueTotal,
            Map<String, BigDecimal> gradeDueTotals, Map<String, Integer> statusCounts) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateChain rates;
    private final SheetRepository sheets;
    private final PeriodRepository periods;
    private final SheetHistory history;
    private final EntityManager entities;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, RateChain rates, SheetRepository sheets,
            PeriodRepository periods, SheetHistory history, EntityManager entities, Audit audit) {
        this.periods = periods;
        this.history = history;
        this.entities = entities;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's figures as the store holds them now. */
    public Snapshot snapshot(String sheetId) {
        Sheet sheet = sheet(sheetId);
        LocalDate today = sheet.getToday();
        List<List<Rate>> sheetRates = rates.current(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Map<Long, Figures> figures = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            BigDecimal overdue = zero();
            boolean anyFigure = false;
            for (Claim claim : sheetClaims) {
                if (!claim.getPersonId().equals(person.getId())) {
                    continue;
                }
                if (!person.counts(claim, today)) {
                    continue;
                }
                if (!claim.isCorrection()) {
                    claimed = claimed.add(claim.getAmount());
                }
                BigDecimal figure = figure(sheet, sheetRates, sheetClaims, person, claim);
                if (figure != null) {
                    anyFigure = true;
                    due = due.add(figure);
                    if (overdue(sheet, person, claim)) {
                        overdue = overdue.add(figure);
                    }
                }
            }
            // as on screen: n/a when the grade in force today has no rate today and no claim that
            // counts has a figure
            boolean none = inForce(sheetRates, person.gradeOn(today), today).isEmpty() && !anyFigure;
            figures.put(person.getId(), new Figures(person.getName(), claimed, none ? null : sheet.capped(due),
                    overdue, person.gradeOn(today), person.isRetired()));
        }
        return new Snapshot(figures);
    }

    /**
     * The figures of the sheet's descendants (its children, their children and so on; not the sheet
     * itself), each sheet's due- and overdue-total as that sheet shows it, added up. When the request
     * is answered as of a date (see {@link AsOfFilter}), each descendant is first restored as it stood
     * at the end of that day, its today the date, in the request's rolled-back transaction; the tree
     * itself (each sheet's parent) is as it is now.
     */
    public Consolidated descendants(String sheetId) {
        Set<String> seen = new HashSet<>(Set.of(sheetId));
        Deque<String> pending = new ArrayDeque<>(List.of(sheetId));
        List<String> tree = new ArrayList<>();
        while (!pending.isEmpty()) {
            for (Sheet child : sheets.findByParentIdOrderByIdAsc(pending.pop())) {
                if (seen.add(child.getId())) {
                    tree.add(child.getId());
                    pending.add(child.getId());
                }
            }
        }
        Optional<LocalDate> asOf = AsOfFilter.current();
        if (asOf.isPresent()) {
            entities.flush();
            tree.forEach(id -> history.restore(id, asOf.get()));
            // the rows were put back behind the persistence context's back: read them afresh
            entities.clear();
        }
        int count = 0;
        BigDecimal due = zero();
        BigDecimal overdue = zero();
        Map<String, BigDecimal> grades = new LinkedHashMap<>();
        Map<String, Integer> statuses = new LinkedHashMap<>();
        for (String id : tree) {
            Snapshot figures = snapshot(id);
            count += figures.people().size();
            due = due.add(figures.dueTotal());
            overdue = overdue.add(figures.overdueTotal());
            figures.gradeDueTotals().forEach((grade, total) -> grades.merge(grade, total, BigDecimal::add));
            statusCounts(id).forEach((status, n) -> statuses.merge(status, n, Integer::sum));
        }
        return new Consolidated(count, due, overdue, grades, statuses);
    }

    /** Per status, how many of the sheet's claims as the store holds them are in it (retired people's included). */
    public Map<String, Integer> statusCounts(String sheetId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            counts.merge(claim.getStatus().name(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Each person's due of their claims whose due-by falls in from..to (both inclusive) and that
     * counted by the status they had at the end of the to-day (as their status log records it; a
     * claim with none by then does not), by person id, for every person on the sheet (0.00 when
     * none). Each counts at its figure as shown now.
     */
    public Map<Long, BigDecimal> dueBetweenByPerson(String sheetId, LocalDate from, LocalDate to) {
        Sheet sheet = sheet(sheetId);
        List<List<Rate>> sheetRates = rates.current(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Map<Long, BigDecimal> dues = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            BigDecimal due = zero();
            for (Claim claim : sheetClaims) {
                if (!claim.getPersonId().equals(person.getId()) || claim.getDueBy().isBefore(from)
                        || claim.getDueBy().isAfter(to) || !claim.statusAt(to)
                                .map(status -> person.counts(claim, status, sheet.getToday())).orElse(false)) {
                    continue;
                }
                BigDecimal figure = figure(sheet, sheetRates, sheetClaims, person, claim);
                if (figure != null) {
                    due = due.add(figure);
                }
            }
            dues.put(person.getId(), due);
        }
        return dues;
    }

    /**
     * Each person's carried figure for a period: the due of their claims that belong to a period of
     * the sheet ending before this one starts and that counted and were still OPEN or PENDING at the
     * end of this period's to-date (as their status log records it), by person id, for every person on
     * the sheet (0.00 when none). Each counts at its figure as shown now. The person's opening balance
     * for the period is added to it.
     */
    public Map<Long, BigDecimal> carriedByPerson(String sheetId, Period period) {
        Sheet sheet = sheet(sheetId);
        List<Period> earlier = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> p.getToDate().isBefore(period.getFromDate())).toList();
        List<List<Rate>> sheetRates = rates.current(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        LocalDate end = period.getToDate();
        Map<Long, BigDecimal> carried = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            BigDecimal due = zero();
            for (Claim claim : sheetClaims) {
                LocalDate dueBy = claim.getDueBy();
                if (!claim.getPersonId().equals(person.getId()) || dueBy == null
                        || earlier.stream().noneMatch(p -> !dueBy.isBefore(p.getFromDate())
                                && !dueBy.isAfter(p.getToDate()))
                        || !claim.statusAt(end).map(status -> status.isOpen()
                                && person.counts(claim, status, sheet.getToday())).orElse(false)) {
                    continue;
                }
                BigDecimal figure = figure(sheet, sheetRates, sheetClaims, person, claim);
                if (figure != null) {
                    due = due.add(figure);
                }
            }
            carried.put(person.getId(), due.add(period.getOpenings().getOrDefault(person.getId(), zero())));
        }
        return carried;
    }

    /** Record, once the change commits, the figures that moved since the snapshot taken before it. */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        recordChanges(sheetId, user, before, false);
    }

    /** Record, once the change commits, the figures of every person on the sheet in list order, then its totals. */
    public void recordAll(String sheetId, String user) {
        Snapshot now = snapshot(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        for (Figures figures : now.people().values()) {
            audit.recordOnCommit("ROLLUP" + prefix + " person=" + figures.name() + " claimed="
                    + money(figures.claimed()) + " due-total=" + money(figures.dueTotal()) + " overdue-total="
                    + money(figures.overdueTotal()));
        }
        audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + money(now.claimedTotal()) + " due-total="
                + money(now.dueTotal()) + " overdue-total=" + money(now.overdueTotal()));
        recordAlerts(sheetId, prefix, new Snapshot(Map.of()), now);
    }

    /**
     * Record, once the change commits, the figures that moved since the snapshot taken before it;
     * with {@code leaving}, a person who has left the sheet since counts as moved, so the totals
     * they leave are recorded too.
     */
    public void recordChanges(String sheetId, String user, Snapshot before, boolean leaving) {
        Snapshot after = snapshot(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        boolean moved = leaving && !after.people().keySet().containsAll(before.people().keySet());
        for (Map.Entry<Long, Figures> entry : after.people().entrySet()) {
            Figures now = entry.getValue();
            Figures was = before.people().get(entry.getKey());
            if (was != null && same(was.claimed(), now.claimed()) && same(was.dueTotal(), now.dueTotal())
                    && same(was.overdueTotal(), now.overdueTotal())) {
                continue;
            }
            moved = true;
            audit.recordOnCommit("ROLLUP" + prefix + " person=" + now.name() + " claimed=" + money(now.claimed())
                    + " due-total=" + money(now.dueTotal()) + " overdue-total=" + money(now.overdueTotal()));
        }
        if (moved && !(same(before.claimedTotal(), after.claimedTotal())
                && same(before.dueTotal(), after.dueTotal()) && same(before.overdueTotal(), after.overdueTotal()))) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + money(after.claimedTotal()) + " due-total="
                    + money(after.dueTotal()) + " overdue-total=" + money(after.overdueTotal()));
        }
        recordAlerts(sheetId, prefix, before, after);
    }

    /**
     * Record one ALERT per person, in list order, whose due-total went from at or below the sheet's
     * alert threshold (or none) to above it; none when the threshold is off.
     */
    private void recordAlerts(String sheetId, String prefix, Snapshot before, Snapshot after) {
        BigDecimal threshold = sheet(sheetId).getAlertThreshold();
        if (threshold == null) {
            return;
        }
        for (Map.Entry<Long, Figures> entry : after.people().entrySet()) {
            Figures now = entry.getValue();
            Figures was = before.people().get(entry.getKey());
            if (above(now.dueTotal(), threshold) && (was == null || !above(was.dueTotal(), threshold))) {
                audit.recordOnCommit("ALERT" + prefix + " name=" + now.name() + " due-total=" + money(now.dueTotal()));
            }
        }
    }

    private static boolean above(BigDecimal dueTotal, BigDecimal threshold) {
        return dueTotal != null && dueTotal.compareTo(threshold) > 0;
    }

    /**
     * A claim's figure as the screen shows it: the due fixed when settled, else the due as shown bound by the
     * last statement (late charge included), else its live due (rounded as the claim rounds, see {@link Rounding}), raised to the floor (a correction's is not), with its late charge on that added on top;
     * null when none.
     */
    private static BigDecimal figure(Sheet sheet, List<List<Rate>> sheetRates, List<Claim> sheetClaims, Person person,
            Claim claim) {
        if (claim.getStatus() == ClaimStatus.SETTLED && claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        if (claim.boundCharged() != null) {
            return claim.boundCharged();
        }
        BigDecimal figure = claim.isStated() ? claim.getStatedDue()
                : RateChain.inForce(sheetRates, person.gradeOn(claim.getDate()), claim).map(Rate::getPct)
                        .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2,
                                Rounding.mode(sheet, Rounding.dateOf(claim, sheetClaims))))
                        .orElse(null);
        return LateCharge.withCharge(sheet, claim, person.gradeOn(claim.getDate()), figure);
    }

    /**
     * Whether a claim is overdue on the sheet's today: OPEN or PENDING, not deferred, and past its due-by plus the
     * grace days of its person's grade on its date (see {@link Sheet#lateFrom}).
     */
    private static boolean overdue(Sheet sheet, Person person, Claim claim) {
        LocalDate today = sheet.getToday();
        return claim.getStatus().isOpen() && !claim.isDeferredOn(today) && today != null && claim.getDueBy() != null
                && sheet.lateFrom(claim.getDueBy(), claim.getDate(), person.gradeOn(claim.getDate())).isBefore(today);
    }

    private Sheet sheet(String sheetId) {
        return sheets.findById(sheetId).orElseGet(() -> new Sheet(sheetId, null, null, LocalDate.now()));
    }

    /** The grade's rate in force on a date, on the sheet or failing that its nearest ancestor (see {@link RateChain}). */
    private static Optional<Rate> inForce(List<List<Rate>> sheetRates, String grade, LocalDate date) {
        return RateChain.inForce(sheetRates, grade, date);
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String money(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
