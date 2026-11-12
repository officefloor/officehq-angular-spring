package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.StatusChange;
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
 * added, and a TOTALS line when the sheet's totals moved.
 */
@Service
public class Rollup {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** A person's figures; a null due-total is n/a. */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    /** Everyone's figures by person id, in the order they were added, and the sheet's totals. */
    public record Snapshot(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal,
            BigDecimal overdueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final Audit audit;

    public Rollup(PersonRepository people, ClaimRepository claims, RateRepository rates, SheetRepository sheets,
            Audit audit) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's figures now. */
    public Snapshot take(String sheetId) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        LocalDate today = sheet.map(Sheet::getToday).orElse(null);
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAsc(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = ZERO;
        List<BigDecimal> dues = new ArrayList<>();
        List<BigDecimal> overdues = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            BigDecimal claimed = ZERO;
            BigDecimal due = ZERO;
            BigDecimal overdue = ZERO;
            boolean keptFigure = false;
            for (Claim claim : sheetClaims) {
                if (!claim.getPersonId().equals(person.getId()) || !countsLive(claim, person, today)) {
                    continue;
                }
                claimed = claimed.add(claim.getAmount());
                BigDecimal kept = claim.getSettledDue() != null ? claim.getSettledDue()
                        : claim.isBound() ? claim.getStatedDue() : null;
                BigDecimal figure = null;
                if (kept != null) {
                    keptFigure = true;
                    figure = charged(sheet, claim, kept);
                } else if (!claim.isBound()) {
                    // a claim priced at the grade in force on its date keeps a figure too
                    BigDecimal live = inForce(sheetRates, person.gradeOn(claim.getDate()), claim.getDate())
                            .map(rate -> rated(sheet, claim, sheetClaims, rate))
                            .orElse(null);
                    if (live != null) {
                        keptFigure = true;
                        figure = charged(sheet, claim, live);
                    }
                }
                if (figure != null) {
                    due = due.add(figure);
                    if (overdue(claim, today)) {
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

    /** Each person's due-total of their claims dated from one date to another, both inclusive, by person
     *  id, every person on the sheet in the order they were added, as the screen shows a person's for a
     *  period: a claim counts by the status it had at the end of the last day, as its status log records
     *  it (one added after then does not), unless a hold or a deferral leaves it out now; each counts its
     *  due as shown now: its settled due, else the figure its statement bound, else its live due, an
     *  overdue claim's late charge on top, at least the floor (a claim with no figure adds nothing). */
    public Map<Long, BigDecimal> dueBetweenByPerson(String sheetId, LocalDate from, LocalDate to) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        LocalDate today = sheet.map(Sheet::getToday).orElse(null);
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAsc(sheetId);
        Map<Long, Person> byId = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        people.findBySheetIdOrderByIdAsc(sheetId).forEach(p -> {
            byId.put(p.getId(), p);
            totals.put(p.getId(), ZERO);
        });
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        for (Claim claim : sheetClaims) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || claim.getDate().isBefore(from) || claim.getDate().isAfter(to)
                    || !countsAt(claim, to) || heldOut(claim, person, today) || claim.deferredOn(today)) {
                continue;
            }
            BigDecimal due;
            if (claim.getSettledDue() != null) {
                due = claim.getSettledDue();
            } else if (claim.isBound()) {
                due = claim.getStatedDue();
            } else {
                due = inForce(sheetRates, person.gradeOn(claim.getDate()), claim.getDate())
                        .map(rate -> rated(sheet, claim, sheetClaims, rate))
                        .orElse(null);
            }
            if (due != null) {
                totals.merge(person.getId(), charged(sheet, claim, due), BigDecimal::add);
            }
        }
        return totals;
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
    }

    /** Whether a claim counts in the live totals: its status counts, it is not left out by a hold, and it
     *  is not deferred past the sheet's today. */
    private static boolean countsLive(Claim claim, Person person, LocalDate today) {
        return claim.getStatus().counts() && !heldOut(claim, person, today) && !claim.deferredOn(today);
    }

    /** Whether a claim counted by the status it had at the end of a day, as its status log records it:
     *  its latest line dated on or before it; not when it was added after it. A claim with no log counts
     *  by its status now. */
    private static boolean countsAt(Claim claim, LocalDate day) {
        List<StatusChange> log = claim.getStatusLog();
        if (log.isEmpty()) {
            return claim.getStatus().counts();
        }
        ClaimStatus status = null;
        for (StatusChange change : log) {
            if (!change.getOn().isAfter(day)) {
                status = change.getStatus();
            }
        }
        return status != null && status.counts();
    }

    /** Whether a claim is overdue: OPEN or PENDING, not deferred, with its due-by before the sheet's today. */
    private static boolean overdue(Claim claim, LocalDate today) {
        return claim.getStatus().isOpen() && !claim.deferredOn(today) && today != null && claim.getDueBy() != null
                && claim.getDueBy().isBefore(today);
    }

    /** Whether a hold or a retirement leaves a claim out of the live totals: it is OPEN or PENDING, bound by
     *  no statement, and dated within one of its person's holds or its person's retirement date is reached on
     *  the sheet's today. */
    public static boolean heldOut(Claim claim, Person person, LocalDate today) {
        return person != null && claim.getStatus().isOpen() && !claim.isBound()
                && (person.heldOn(claim.getDate()) || person.retiredBy(today));
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

    /** A claim's due from its figure (its rate figure, or the one its statement bound): its late charge on
     *  top while it is OPEN or PENDING (only those go overdue), then at least the sheet's floor in force on its
     *  date. A settled claim's due is frozen (it already holds the charge and floor fixed when it was
     *  settled); a correction has neither late charge nor floor. */
    public static BigDecimal charged(Optional<Sheet> sheet, Claim claim, BigDecimal figure) {
        if (claim.getSettledDue() != null || claim.isCorrection()) {
            return figure;
        }
        return sheet.map(s -> s.floored(claim.getStatus().isOpen()
                ? figure.add(s.lateCharge(figure, claim.getDueBy(), claim.getDate())) : figure, claim.getDate()))
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

    /** The grade's rate in force on a date: the latest from-date on or before it, none first; none
     *  when the date is after that rate's to-date. */
    public static Optional<Rate> inForce(List<Rate> sheetRates, String grade, LocalDate date) {
        return sheetRates.stream()
                .filter(r -> r.getGrade().equals(grade))
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.coversThrough(date));
    }
}
