package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.StatusChange;
import net.officefloor.hq.app.history.AsOf;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * Each person's claimed, due-total and overdue-total on a sheet, and the sheet's totals, as the screen
 * shows them.
 * A change takes the figures before it, then records after its own audit lines a ROLLUP line for each
 * person whose figures moved (a person new to the sheet counting as moved), in the order they were
 * added, and a TOTALS line when the sheet's totals moved, then an ALERT line for each person whose due-total
 * went from at or below the sheet's alert threshold (or none) to above it, in the list order.
 */
@Service
public class Rollup {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** A person's figures; a null due-total is n/a. */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    /** Everyone's figures by person id, in list order, and the sheet's totals. */
    public record Snapshot(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal,
            BigDecimal overdueTotal) {
    }

    /** Figures added up over sheets: the number of people, and each sheet's due- and overdue-total. */
    public record Consolidated(int peopleCount, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final AsOf asOf;
    private final Audit audit;

    public Rollup(PersonRepository people, ClaimRepository claims, RateRepository rates, SheetRepository sheets,
            AsOf asOf, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.asOf = asOf;
        this.audit = audit;
    }

    /** The sheet's figures now. */
    public Snapshot take(String sheetId) {
        return take(sheets.findById(sheetId), people.findBySheetIdOrderByPositionAscIdAsc(sheetId),
                claims.findBySheetIdOrderByIdDesc(sheetId), rates.findWithAncestors(sheetId, sheets.ancestors(sheetId)));
    }

    /** The sheet's figures as it stood at the end of a date, that date its today: its rows, and its ancestors'
     *  rates, each as of the date. */
    public Snapshot take(String sheetId, LocalDate date) {
        List<Rate> sheetRates = new ArrayList<>(asOf.rates(sheetId, date));
        sheets.ancestors(sheetId).forEach(a -> sheetRates.addAll(asOf.rates(a, date)));
        return take(Optional.of(asOf.sheet(sheetId, date)), asOf.people(sheetId, date), asOf.claims(sheetId, date),
                sheetRates);
    }

    private Snapshot take(Optional<Sheet> sheet, List<Person> sheetPeople, List<Claim> sheetClaims, List<Rate> sheetRates) {
        LocalDate today = sheet.map(Sheet::getToday).orElse(null);
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = ZERO;
        List<BigDecimal> dues = new ArrayList<>();
        List<BigDecimal> overdues = new ArrayList<>();
        for (Person person : sheetPeople) {
            BigDecimal claimed = ZERO;
            BigDecimal due = ZERO;
            BigDecimal overdue = ZERO;
            boolean keptFigure = false;
            for (Claim claim : sheetClaims) {
                if (!claim.getPersonId().equals(person.getId()) || !countsClaimed(claim, person, today)) {
                    continue;
                }
                if (claim.getCorrects() == null || claim.getCorrects().isEmpty()) {
                    // a claim that corrects another is left out of claimed
                    claimed = claimed.add(claim.getAmount());
                }
                if (heldOut(claim, person, today)) {
                    // a hold leaves a claim in claimed, but out of the due figures
                    continue;
                }
                BigDecimal kept = claim.getSettledDue() != null ? claim.getSettledDue()
                        : claim.isBound() ? claim.getStatedDue() : null;
                BigDecimal figure = null;
                if (kept != null) {
                    keptFigure = true;
                    figure = charged(sheet, claim, person, kept);
                } else if (!claim.isBound()) {
                    // a claim priced at the grade in force on its date keeps a figure too
                    BigDecimal live = inForce(sheetRates, person.gradeOn(claim.getDate()), claim.getDate(), claim)
                            .map(rate -> rated(sheet, claim, sheetClaims, rate))
                            .orElse(null);
                    if (live != null) {
                        keptFigure = true;
                        figure = charged(sheet, claim, person, live);
                    }
                }
                if (figure != null) {
                    due = due.add(figure);
                    if (overdue(sheet, claim, person, today)) {
                        overdue = overdue.add(figure);
                    }
                }
            }
            boolean rated = today != null && inForce(sheetRates, person.gradeOn(today), today).isPresent();
            BigDecimal personDue = rated || keptFigure ? capped(sheet, due) : null;
            figures.put(person.getId(), new Figures(person.getName(), claimed, personDue, overdue));
            claimedTotal = claimedTotal.add(claimed);
            overdues.add(overdue);
            if (personDue != null) {
                dues.add(personDue);
            }
        }
        return new Snapshot(figures, claimedTotal, trimmed(dues), trimmed(overdues));
    }

    /** The figures of every descendant of a sheet (its children, their children and so on; the sheet itself
     *  left out), each sheet's due- and overdue-total as that sheet shows it, added up. */
    public Consolidated descendants(String sheetId) {
        return descendants(sheetId, null);
    }

    /** The figures of every descendant of a sheet, as {@link #descendants(String)}, each sheet as it stood at the
     *  end of a date, that date its today; as they are now with no date. */
    public Consolidated descendants(String sheetId, LocalDate date) {
        Set<String> seen = new LinkedHashSet<>(List.of(sheetId));
        List<String> todo = new ArrayList<>(List.of(sheetId));
        int peopleCount = 0;
        BigDecimal due = ZERO;
        BigDecimal overdue = ZERO;
        while (!todo.isEmpty()) {
            for (Sheet child : sheets.findByParentOrderByIdAsc(todo.remove(0))) {
                if (!seen.add(child.getId())) {
                    continue;
                }
                todo.add(child.getId());
                Snapshot snapshot = date == null ? take(child.getId()) : take(child.getId(), date);
                peopleCount += snapshot.people().size();
                due = due.add(snapshot.dueTotal());
                overdue = overdue.add(snapshot.overdueTotal());
            }
        }
        return new Consolidated(peopleCount, due, overdue);
    }

    /** People's figures added up, leaving out the single highest and the single lowest when there are three
     *  or more. */
    static BigDecimal trimmed(List<BigDecimal> figures) {
        BigDecimal sum = figures.stream().reduce(ZERO, BigDecimal::add);
        if (figures.size() < 3) {
            return sum;
        }
        BigDecimal max = figures.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal min = figures.stream().min(BigDecimal::compareTo).orElseThrow();
        return sum.subtract(max).subtract(min);
    }

    /** Each person's due-total of their claims due by a day from one date to another, both inclusive, by person
     *  id, every person on the sheet in list order, as the screen shows a person's for a
     *  period: a claim counts by the status it had at the end of the last day, as its status log records
     *  it (one added after then does not), unless a hold or a deferral leaves it out now; each counts its
     *  due as shown now: its settled due, else the figure its statement bound, else its live due, an
     *  overdue claim's late charge on top, at least the floor (a claim with no figure adds nothing). */
    public Map<Long, BigDecimal> dueBetweenByPerson(String sheetId, LocalDate from, LocalDate to) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        LocalDate today = sheet.map(Sheet::getToday).orElse(null);
        List<Rate> sheetRates = rates.findWithAncestors(sheetId, sheets.ancestors(sheetId));
        Map<Long, Person> byId = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(p -> {
            byId.put(p.getId(), p);
            totals.put(p.getId(), ZERO);
        });
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        for (Claim claim : sheetClaims) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || claim.getDueBy().isBefore(from) || claim.getDueBy().isAfter(to)
                    || !countsAt(claim, to) || heldOut(claim, person, today) || claim.deferredOn(today)) {
                continue;
            }
            BigDecimal due;
            if (claim.getSettledDue() != null) {
                due = claim.getSettledDue();
            } else if (claim.isBound()) {
                due = claim.getStatedDue();
            } else {
                due = inForce(sheetRates, person.gradeOn(claim.getDate()), claim.getDate(), claim)
                        .map(rate -> rated(sheet, claim, sheetClaims, rate))
                        .orElse(null);
            }
            if (due != null) {
                totals.merge(person.getId(), charged(sheet, claim, person, due), BigDecimal::add);
            }
        }
        return totals;
    }

    /** Each person's carried figure for a period, by person id, every person on the sheet in list order, as the
     *  screen shows it while the period is open: the due (as {@link #dueBetweenByPerson} counts it) of their
     *  claims not left out now by a hold or a deferral whose due-by falls in one of the sheet's periods ending
     *  before this one starts, and that were still OPEN or PENDING at the end of this period's last day, as
     *  their status log records it; and their opening balance for the period on top. */
    public Map<Long, BigDecimal> carriedByPerson(String sheetId, Period period, List<Period> sheetPeriods) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        LocalDate today = sheet.map(Sheet::getToday).orElse(null);
        List<Rate> sheetRates = rates.findWithAncestors(sheetId, sheets.ancestors(sheetId));
        Map<Long, Person> byId = new LinkedHashMap<>();
        Map<Long, BigDecimal> carried = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(p -> {
            byId.put(p.getId(), p);
            carried.put(p.getId(), ZERO);
        });
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        for (Claim claim : sheetClaims) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || claim.getDueBy() == null || heldOut(claim, person, today) || claim.deferredOn(today)) {
                continue;
            }
            Optional<Period> own = sheetPeriods.stream().filter(p -> p.contains(claim.getDueBy())).findFirst();
            if (own.isEmpty() || !own.get().getToDate().isBefore(period.getFromDate())) {
                continue;
            }
            ClaimStatus status = statusAt(claim, period.getToDate());
            if (status == null || !status.isOpen()) {
                continue;
            }
            BigDecimal due;
            if (claim.getSettledDue() != null) {
                due = claim.getSettledDue();
            } else if (claim.isBound()) {
                due = claim.getStatedDue();
            } else {
                due = inForce(sheetRates, person.gradeOn(claim.getDate()), claim.getDate(), claim)
                        .map(rate -> rated(sheet, claim, sheetClaims, rate))
                        .orElse(null);
            }
            if (due != null) {
                carried.merge(person.getId(), charged(sheet, claim, person, due), BigDecimal::add);
            }
        }
        period.getOpenings().forEach((id, opening) -> carried.computeIfPresent(id, (k, c) -> c.add(opening)));
        return carried;
    }

    /** Record, once the change commits, a SNAPSHOT line of each person's due-total in a snapshot, in the
     *  list order. */
    public void recordSnapshot(String sheetId, String user, Snapshot snapshot) {
        String prefix = " sheet=" + sheetId + " by=" + user;
        snapshot.people().values().forEach(figures -> audit.recordOnCommit("SNAPSHOT" + prefix
                + " name=" + figures.name() + " due-total=" + show(figures.dueTotal())));
    }

    /** Record, once the change commits, a ROLLUP line of every person's figures in the list order, then the
     *  TOTALS line, whether or not they moved. */
    public void recordAll(String sheetId, String user) {
        Snapshot now = take(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        now.people().values().forEach(figures -> audit.recordOnCommit("ROLLUP" + prefix + " person=" + figures.name()
                + " claimed=" + figures.claimed().toPlainString() + " due-total=" + show(figures.dueTotal())
                + " overdue-total=" + figures.overdueTotal().toPlainString()));
        audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + now.claimedTotal().toPlainString()
                + " due-total=" + now.dueTotal().toPlainString()
                + " overdue-total=" + now.overdueTotal().toPlainString());
        recordAlerts(sheetId, prefix, null, now);
    }

    /** Record, once the change commits, the lines for every figure that moved since {@code before}. */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        Snapshot after = take(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        after.people().forEach((id, now) -> {
            Figures was = before.people().get(id);
            if (was == null || was.claimed().compareTo(now.claimed()) != 0
                    || !sameDue(was.dueTotal(), now.dueTotal())
                    || was.overdueTotal().compareTo(now.overdueTotal()) != 0) {
                audit.recordOnCommit("ROLLUP" + prefix + " person=" + now.name()
                        + " claimed=" + now.claimed().toPlainString() + " due-total=" + show(now.dueTotal())
                        + " overdue-total=" + now.overdueTotal().toPlainString());
            }
        });
        if (before.claimedTotal().compareTo(after.claimedTotal()) != 0
                || before.dueTotal().compareTo(after.dueTotal()) != 0
                || before.overdueTotal().compareTo(after.overdueTotal()) != 0) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + after.claimedTotal().toPlainString()
                    + " due-total=" + after.dueTotal().toPlainString()
                    + " overdue-total=" + after.overdueTotal().toPlainString());
        }
        recordAlerts(sheetId, prefix, before, after);
    }

    /** Record, once the change commits, an ALERT line for each person whose due-total went from at or below the
     *  sheet's alert threshold (or none, or new to the sheet) to above it, in the list order; none when the
     *  threshold is off. */
    private void recordAlerts(String sheetId, String prefix, Snapshot before, Snapshot after) {
        BigDecimal threshold = sheets.findById(sheetId).map(Sheet::getAlertThreshold).orElse(null);
        if (threshold == null) {
            return;
        }
        after.people().forEach((id, now) -> {
            Figures was = before == null ? null : before.people().get(id);
            if (above(now.dueTotal(), threshold) && (was == null || !above(was.dueTotal(), threshold))) {
                audit.recordOnCommit("ALERT" + prefix + " name=" + now.name() + " due-total=" + show(now.dueTotal()));
            }
        });
    }

    private static boolean above(BigDecimal dueTotal, BigDecimal threshold) {
        return dueTotal != null && dueTotal.compareTo(threshold) > 0;
    }

    /** Whether a claim counts toward claimed: its status counts, it is not left out by its person's
     *  retirement (a hold leaves it in), and it is not deferred past the sheet's today. */
    private static boolean countsClaimed(Claim claim, Person person, LocalDate today) {
        return claim.getStatus().counts() && !retiredOut(claim, person, today) && !claim.deferredOn(today);
    }

    /** Whether a claim counted by the status it had at the end of a day, as its status log records it:
     *  its latest line dated on or before it; not when it was added after it. A claim with no log counts
     *  by its status now. */
    private static boolean countsAt(Claim claim, LocalDate day) {
        ClaimStatus status = statusAt(claim, day);
        return status != null && status.counts();
    }

    /** The status a claim had at the end of a day, as its status log records it: its latest line dated on or
     *  before it; none when it was added after it. A claim with no log has its status now. */
    private static ClaimStatus statusAt(Claim claim, LocalDate day) {
        List<StatusChange> log = claim.getStatusLog();
        if (log.isEmpty()) {
            return claim.getStatus();
        }
        ClaimStatus status = null;
        for (StatusChange change : log) {
            if (!change.getOn().isAfter(day)) {
                status = change.getStatus();
            }
        }
        return status;
    }

    /** Whether a claim is overdue: OPEN or PENDING, not deferred, with its due-by plus the grace days of its
     *  person's grade in force on its date before the sheet's today. */
    private static boolean overdue(Optional<Sheet> sheet, Claim claim, Person person, LocalDate today) {
        return claim.getStatus().isOpen() && !claim.deferredOn(today) && today != null && claim.getDueBy() != null
                && lateFrom(sheet, claim, person).isBefore(today);
    }

    /** The date a claim's lateness runs from: its due-by plus the grace days in force on its date of its
     *  person's grade in force then. */
    public static LocalDate lateFrom(Optional<Sheet> sheet, Claim claim, Person person) {
        String grade = person == null ? null : person.gradeOn(claim.getDate());
        return sheet.map(s -> s.lateFrom(claim.getDueBy(), grade, claim.getDate())).orElse(claim.getDueBy());
    }

    /** Whether a retirement leaves a claim out of claimed: it is OPEN or PENDING, bound by no statement, and
     *  its person's retirement date is reached on the sheet's today. */
    private static boolean retiredOut(Claim claim, Person person, LocalDate today) {
        return person != null && claim.getStatus().isOpen() && !claim.isBound() && person.retiredBy(today);
    }

    /** Whether a hold or a retirement leaves a claim out of the live totals: it is OPEN or PENDING, bound by
     *  no statement, and held itself, or dated within one of its person's holds, or its person's retirement
     *  date is reached on the sheet's today. */
    public static boolean heldOut(Claim claim, Person person, LocalDate today) {
        return person != null && claim.getStatus().isOpen() && !claim.isBound()
                && (claim.isHeld() || person.heldOn(claim.getDate()) || person.retiredBy(today));
    }

    /** A claim's amount at a rate, divided by 100, rounded to cents as the claim's rounding date rounds on
     *  the sheet (half to even with no sheet). */
    public static BigDecimal rated(Optional<Sheet> sheet, Claim claim, List<Claim> sheetClaims, Rate rate) {
        LocalDate on = roundingDate(claim, sheetClaims);
        return claim.getAmount().multiply(rate.getRate()).movePointLeft(2)
                .setScale(2, sheet.map(s -> s.rounding(on)).orElse(RoundingMode.HALF_EVEN));
    }

    /** The date a claim's figures round by: its own, or for a correction the date of the claim it
     *  corrects (the claim of the same person with that number), its own when that is not found. */
    public static LocalDate roundingDate(Claim claim, List<Claim> sheetClaims) {
        if (!claim.isCorrection()) {
            return claim.getDate();
        }
        return sheetClaims.stream()
                .filter(c -> c.getPersonId().equals(claim.getPersonId()) && claim.getCorrects().equals(c.getNumber()))
                .findFirst().map(Claim::getDate).orElse(claim.getDate());
    }

    /** A claim's due from its figure: at least the sheet's floor in force on its date, then its late charge,
     *  worked out on that floored figure, on top while it is OPEN or PENDING (only those go overdue). A settled claim's due is frozen (it
     *  already holds the charge and floor fixed when it was settled), as is a bound one's (its statement bound
     *  the due as shown then, charge and floor included); a correction has neither late charge nor floor, and a
     *  waived claim no late charge. */
    public static BigDecimal charged(Optional<Sheet> sheet, Claim claim, Person person, BigDecimal figure) {
        if (claim.getSettledDue() != null || claim.isBound() || claim.isCorrection()) {
            return figure;
        }
        return sheet.map(s -> {
                    BigDecimal floored = s.floored(figure, claim.getDate());
                    return claim.getStatus().isOpen() && !claim.isWaived()
                            ? floored.add(s.lateCharge(floored, lateFrom(sheet, claim, person), claim.getDate())) : floored;
                })
                .orElse(figure);
    }

    /** A person's due-total at most the sheet's cap. */
    private static BigDecimal capped(Optional<Sheet> sheet, BigDecimal dueTotal) {
        return sheet.map(s -> s.capped(dueTotal)).orElse(dueTotal);
    }

    private static boolean sameDue(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? Objects.equals(a, b) : a.compareTo(b) == 0;
    }

    private static String show(BigDecimal due) {
        return due == null ? "n/a" : due.toPlainString();
    }

    /** The grade's rate in force on a date for a claim: as {@link #inForce(List, String, LocalDate)}, each rate
     *  as it applies to the claim (a forward-only rate not to a claim already on the sheet when it was saved,
     *  which keeps what applied to it). */
    public static Optional<Rate> inForce(List<Rate> sheetRates, String grade, LocalDate date, Claim claim) {
        List<Rate> forClaim = new ArrayList<>();
        sheetRates.forEach(r -> r.forClaim(claim.getId()).ifPresent(forClaim::add));
        return inForce(forClaim, grade, date);
    }

    /** The grade's rate in force on a date: the latest from-date on or before it, none first; none
     *  when the date is after that rate's to-date. The rates may be a sheet's followed by its ancestors',
     *  nearest first: the first sheet, in that order, with a rate of the grade in force then gives it. */
    public static Optional<Rate> inForce(List<Rate> sheetRates, String grade, LocalDate date) {
        Set<String> sheetIds = new LinkedHashSet<>();
        sheetRates.forEach(r -> sheetIds.add(r.getSheetId()));
        for (String sheetId : sheetIds) {
            Optional<Rate> found = inForceOn(sheetRates, sheetId, grade, date);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<Rate> inForceOn(List<Rate> sheetRates, String sheetId, String grade, LocalDate date) {
        return sheetRates.stream()
                .filter(r -> r.getSheetId().equals(sheetId) && r.getGrade().equals(grade))
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.coversThrough(date));
    }
}
