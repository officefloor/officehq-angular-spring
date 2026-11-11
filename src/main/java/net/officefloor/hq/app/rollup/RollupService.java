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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimShare;
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
import net.officefloor.hq.app.pool.PoolService;
import org.springframework.beans.factory.ObjectProvider;
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
 * due-total is at most the cap in force on the sheet's today less their cut total (not below 0.00), when there is one. With an alert threshold
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

    /** A person's score: their name and the whole number of points. */
    public record Score(String name, int score) {
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
        static BigDecimal trimmed(List<BigDecimal> figures) {
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
     * per grade the due-totals of the people on it (retired left out), per status the count of claims in it,
     * and how many overdue claims that count there are.
     */
    public record Consolidated(int peopleCount, BigDecimal dueTotal, BigDecimal overdueTotal,
            Map<String, BigDecimal> gradeDueTotals, Map<String, Integer> statusCounts, List<TreePerson> people,
            int overdueCount, BigDecimal settledTotal, BigDecimal openTotal, BigDecimal cutTotal) {
    }

    /**
     * A person of a sheet of the tree, as the consolidated figures take them: their name and aliases,
     * due-total (null for n/a), overdue-total, settled-total, open-total and cut total.
     */
    public record TreePerson(List<String> names, BigDecimal dueTotal, BigDecimal overdueTotal,
            BigDecimal settledTotal, BigDecimal openTotal, BigDecimal cutTotal) {
    }

    /** The sheet's people as the consolidated figures take them, in the order they were added. */
    public List<TreePerson> treePeople(String sheetId) {
        Snapshot figures = snapshot(sheetId);
        Map<Long, BigDecimal> settled = settledOpenByPerson(sheetId, false);
        Map<Long, BigDecimal> open = settledOpenByPerson(sheetId, true);
        Map<Long, BigDecimal> cuts = pools.getObject().cutTotalsNow(sheetId);
        List<TreePerson> out = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            Figures own = figures.people().get(person.getId());
            List<String> names = new ArrayList<>(List.of(person.getName()));
            names.addAll(person.getAliases());
            out.add(new TreePerson(names, own.dueTotal(), own.overdueTotal(), settled.get(person.getId()),
                    open.get(person.getId()), cuts.getOrDefault(person.getId(), zero())));
        }
        return out;
    }

    /**
     * The people of a tree counted once each: people whose names or aliases match (compared without
     * regard to case), directly or through others, are one person.
     */
    public static int distinctPeople(List<TreePerson> tree) {
        Map<String, Integer> owner = new LinkedHashMap<>();
        int[] parent = new int[tree.size()];
        for (int i = 0; i < tree.size(); i++) {
            parent[i] = i;
            for (String name : tree.get(i).names()) {
                Integer other = owner.putIfAbsent(name.toLowerCase(Locale.ROOT), i);
                if (other != null) {
                    parent[root(parent, i)] = root(parent, other);
                }
            }
        }
        int count = 0;
        for (int i = 0; i < tree.size(); i++) {
            if (root(parent, i) == i) {
                count++;
            }
        }
        return count;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            i = parent[i];
        }
        return i;
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateChain rates;
    private final SheetRepository sheets;
    private final PeriodRepository periods;
    private final SheetHistory history;
    private final EntityManager entities;
    private final Audit audit;
    private final ObjectProvider<PoolService> pools;

    public RollupService(PersonRepository people, ClaimRepository claims, RateChain rates, SheetRepository sheets,
            PeriodRepository periods, SheetHistory history, EntityManager entities, Audit audit,
            ObjectProvider<PoolService> pools) {
        this.pools = pools;
        this.periods = periods;
        this.history = history;
        this.entities = entities;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** Per grade, the sheet's due-totals as {@link Snapshot#gradeDueTotals} gives them; empty when the sheet does not exist. */
    public Optional<Map<String, BigDecimal>> gradeDueTotals(String sheetId) {
        return sheets.existsById(sheetId) ? Optional.of(snapshot(sheetId).gradeDueTotals()) : Optional.empty();
    }

    /** The sheet's figures as the store holds them now. */
    public Snapshot snapshot(String sheetId) {
        Sheet sheet = sheet(sheetId);
        LocalDate today = sheet.getToday();
        List<List<Rate>> sheetRates = rates.current(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Map<Long, Figures> figures = new LinkedHashMap<>();
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        Map<Long, BigDecimal> heldParts = heldParts(sheet, sheetRates, sheetClaims, sheetPeople, claim -> true);
        Map<Long, Person> byId = byId(sheetPeople);
        Map<Long, BigDecimal> cutTotals = sheet.getCap() == null ? Map.of() : pools.getObject().cutTotalsNow(sheetId);
        for (Person person : sheetPeople) {
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
                    due = due.add(ownPart(sheet, sheetRates, sheetClaims, byId, claim, figure));
                    if (overdue(sheet, person, claim)) {
                        overdue = overdue.add(figure);
                    }
                }
            }
            BigDecimal held = heldParts.get(person.getId());
            if (held != null) {
                anyFigure = true;
                due = due.add(held);
            }
            // as on screen: n/a when the grade in force today has no rate today and no claim that
            // counts has a figure
            boolean none = inForce(sheetRates, person.gradeOn(today), today).isEmpty() && !anyFigure;
            figures.put(person.getId(), new Figures(person.getName(), claimed, none ? null : sheet.capped(due, cutTotals.get(person.getId())),
                    overdue, person.gradeOn(today), person.isRetired()));
        }
        return new Snapshot(figures);
    }

    /**
     * Each person's score as the screen shows it, in list order: their settled figures (the figures of
     * their SETTLED claims that count and correct nothing) divided by 100, minus 5 for every overdue claim of theirs that counts,
     * rounded half to even to a whole number.
     */
    public List<Score> scores(String sheetId) {
        Sheet sheet = sheet(sheetId);
        LocalDate today = sheet.getToday();
        List<List<Rate>> sheetRates = rates.current(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        List<Score> scores = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            BigDecimal settled = zero();
            int overdueCount = 0;
            for (Claim claim : sheetClaims) {
                if (!claim.getPersonId().equals(person.getId()) || !person.counts(claim, today)) {
                    continue;
                }
                if (overdue(sheet, person, claim)) {
                    overdueCount++;
                }
                if (!claim.getStatus().isOpen() && (claim.getCorrects() == null || claim.getCorrects().isBlank())) {
                    // a correction is left out of the score
                    BigDecimal figure = figure(sheet, sheetRates, sheetClaims, person, claim);
                    if (figure != null) {
                        settled = settled.add(figure);
                    }
                }
            }
            BigDecimal score = settled.movePointLeft(2).subtract(BigDecimal.valueOf(5L * overdueCount))
                    .setScale(0, RoundingMode.HALF_EVEN);
            scores.add(new Score(person.getName(), score.intValueExact()));
        }
        return scores;
    }

    /**
     * The figures of the sheet's descendants (its children, their children and so on; not the sheet
     * itself): their people (each counted once, see {@link #distinctPeople}), and the due- and
     * overdue-totals of all their people, trimmed once over them all; the people themselves go with
     * them, so the screen can trim once over the whole tree with the sheet's own. When the request
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
        Optional<LocalDate> preview = AsOfFilter.previewing();
        if (preview.isPresent()) {
            // every sheet of the tree is previewed on the date, as the sheet itself is (see AsOfFilter)
            entities.flush();
            tree.forEach(id -> history.preview(id, preview.get()));
            entities.clear();
        }
        List<TreePerson> treePeople = new ArrayList<>();
        Map<String, BigDecimal> grades = new LinkedHashMap<>();
        Map<String, Integer> statuses = new LinkedHashMap<>();
        int overdueCount = 0;
        for (String id : tree) {
            treePeople.addAll(treePeople(id));
            overdueCount += overdueCount(id);
            snapshot(id).gradeDueTotals().forEach((grade, total) -> grades.merge(grade, total, BigDecimal::add));
            statusCounts(id).forEach((status, n) -> statuses.merge(status, n, Integer::sum));
        }
        BigDecimal due = Snapshot.trimmed(
                treePeople.stream().map(TreePerson::dueTotal).filter(Objects::nonNull).toList());
        BigDecimal overdue = Snapshot.trimmed(treePeople.stream().map(TreePerson::overdueTotal).toList());
        return new Consolidated(distinctPeople(treePeople), due, overdue, grades, statuses, treePeople,
                overdueCount, Snapshot.trimmed(treePeople.stream().map(TreePerson::settledTotal).toList()),
                Snapshot.trimmed(treePeople.stream().map(TreePerson::openTotal).toList()),
                Snapshot.trimmed(treePeople.stream().map(TreePerson::cutTotal).toList()));
    }

    /**
     * Each person's open-total (the figures of their OPEN and PENDING claims that count) or settled-total
     * (those of their SETTLED claims that count), their own parts with the parts they hold of others',
     * by person id, for every person on the sheet (0.00 when none).
     */
    private Map<Long, BigDecimal> settledOpenByPerson(String sheetId, boolean open) {
        Sheet sheet = sheet(sheetId);
        LocalDate today = sheet.getToday();
        List<List<Rate>> sheetRates = rates.current(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> c.getStatus().isOpen() == open).toList();
        List<Claim> allClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        Map<Long, Person> byId = byId(sheetPeople);
        Map<Long, BigDecimal> held = heldParts(sheet, sheetRates, allClaims, sheetPeople,
                c -> c.getStatus().isOpen() == open);
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (Person person : sheetPeople) {
            BigDecimal total = held.getOrDefault(person.getId(), zero());
            for (Claim claim : sheetClaims) {
                if (!claim.getPersonId().equals(person.getId()) || !person.counts(claim, today)) {
                    continue;
                }
                BigDecimal figure = figure(sheet, sheetRates, allClaims, person, claim);
                if (figure != null) {
                    total = total.add(ownPart(sheet, sheetRates, allClaims, byId, claim, figure));
                }
            }
            out.put(person.getId(), total);
        }
        return out;
    }

    /** How many of the sheet's claims that count are overdue on its today, as the people list's totals count them. */
    public int overdueCount(String sheetId) {
        Sheet sheet = sheet(sheetId);
        LocalDate today = sheet.getToday();
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        int count = 0;
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            for (Claim claim : sheetClaims) {
                if (claim.getPersonId().equals(person.getId()) && person.counts(claim, today)
                        && overdue(sheet, person, claim)) {
                    count++;
                }
            }
        }
        return count;
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
        List<Period> earlier = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> p.getToDate().isBefore(period.getFromDate())).toList();
        Map<Long, BigDecimal> carried = openByPerson(sheetId, earlier, period.getToDate());
        carried.replaceAll((person, due) -> due.add(period.getOpenings().getOrDefault(person, zero())));
        return carried;
    }

    /**
     * Each person's open figure at the end of a day: the due of their claims that belong to one of
     * these periods and that counted and were still OPEN or PENDING at the end of that day (as their
     * status log records it), by person id, for every person on the sheet (0.00 when none). Each
     * counts at its figure as shown now.
     */
    public Map<Long, BigDecimal> openByPerson(String sheetId, List<Period> within, LocalDate end) {
        Sheet sheet = sheet(sheetId);
        List<List<Rate>> sheetRates = rates.current(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Map<Long, BigDecimal> open = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            BigDecimal due = zero();
            for (Claim claim : sheetClaims) {
                LocalDate dueBy = claim.getDueBy();
                if (!claim.getPersonId().equals(person.getId()) || dueBy == null
                        || within.stream().noneMatch(p -> !dueBy.isBefore(p.getFromDate())
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
            open.put(person.getId(), due);
        }
        return open;
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

    /** The part of a claim's figure its own person keeps: the figure less the parts of the people it is shared with. */
    private static BigDecimal ownPart(Sheet sheet, List<List<Rate>> sheetRates, List<Claim> sheetClaims,
            Map<Long, Person> byId, Claim claim, BigDecimal figure) {
        BigDecimal own = figure;
        for (ClaimShare share : claim.getShares()) {
            own = own.subtract(part(sheet, sheetRates, sheetClaims, byId, claim, share, figure).orElse(zero()));
        }
        return own;
    }

    /**
     * A holder's part of a claim: the claim's amount times their share divided by 100, times the rate
     * of the holder's own grade in force on the claim's date divided by 100, rounded as the claim
     * rounds (see {@link Rounding}); none (0.00 off the claim, and no figure for the holder) when their
     * grade has no rate then. A SETTLED claim keeps its parts as frozen: its fixed due times the share
     * divided by 100 (see {@link ClaimShare#partOf}).
     */
    private static Optional<BigDecimal> part(Sheet sheet, List<List<Rate>> sheetRates, List<Claim> sheetClaims,
            Map<Long, Person> byId, Claim claim, ClaimShare share, BigDecimal figure) {
        if (claim.getStatus() == ClaimStatus.SETTLED && claim.getSettledDue() != null) {
            return Optional.of(share.partOf(figure));
        }
        Person holder = byId.get(share.getHolderId());
        if (holder == null) {
            return Optional.empty();
        }
        return RateChain.inForce(sheetRates, holder.gradeOn(claim.getDate()), claim).map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(share.getShare()).multiply(pct)
                        .divide(BigDecimal.valueOf(10_000), 2,
                                Rounding.mode(sheet, Rounding.dateOf(claim, sheetClaims))));
    }

    private static Map<Long, Person> byId(List<Person> sheetPeople) {
        Map<Long, Person> byId = new LinkedHashMap<>();
        sheetPeople.forEach(p -> byId.put(p.getId(), p));
        return byId;
    }

    /** The parts a person holds now of others' claims (see {@link #heldParts}); empty when they hold none. */
    public Optional<BigDecimal> heldPart(String sheetId, long personId) {
        Sheet sheet = sheet(sheetId);
        return Optional.ofNullable(heldParts(sheet, rates.current(sheetId), claims.findBySheetIdOrderByIdDesc(sheetId),
                people.findBySheetIdOrderByPositionAscIdAsc(sheetId), claim -> true).get(personId));
    }

    /**
     * Per holder (by person id), the parts they hold of the claims that count for their own person and
     * have a figure, of the claims taken; a person holding none is absent.
     */
    private static Map<Long, BigDecimal> heldParts(Sheet sheet, List<List<Rate>> sheetRates, List<Claim> sheetClaims,
            List<Person> sheetPeople, Predicate<Claim> taken) {
        Map<Long, Person> byId = byId(sheetPeople);
        Map<Long, BigDecimal> parts = new LinkedHashMap<>();
        for (Claim claim : sheetClaims) {
            Person owner = byId.get(claim.getPersonId());
            if (!taken.test(claim) || claim.getShares().isEmpty() || owner == null || !owner.counts(claim, sheet.getToday())) {
                continue;
            }
            BigDecimal figure = figure(sheet, sheetRates, sheetClaims, owner, claim);
            if (figure == null) {
                continue;
            }
            for (ClaimShare share : claim.getShares()) {
                part(sheet, sheetRates, sheetClaims, byId, claim, share, figure)
                        .ifPresent(part -> parts.merge(share.getHolderId(), part, BigDecimal::add));
            }
        }
        return parts;
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
