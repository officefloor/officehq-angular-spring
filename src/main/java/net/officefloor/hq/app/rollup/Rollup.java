package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * Each person's claimed and due-total on a sheet, and the sheet's totals, as the screen shows them.
 * A change takes the figures before it, then records after its own audit lines a ROLLUP line for each
 * person whose figures moved (a person new to the sheet counting as moved), in the order they were
 * added, and a TOTALS line when the sheet's totals moved.
 */
@Service
public class Rollup {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** A person's figures; a null due-total is n/a. */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal) {
    }

    /** Everyone's figures by person id, in the order they were added, and the sheet's totals. */
    public record Snapshot(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal) {
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
        BigDecimal dueTotal = ZERO;
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            BigDecimal claimed = ZERO;
            BigDecimal due = ZERO;
            boolean keptFigure = false;
            for (Claim claim : sheetClaims) {
                if (!claim.getPersonId().equals(person.getId()) || !countsLive(claim, person)) {
                    continue;
                }
                claimed = claimed.add(claim.getAmount());
                BigDecimal kept = claim.getSettledDue() != null ? claim.getSettledDue()
                        : claim.isBound() ? claim.getStatedDue() : null;
                if (kept != null) {
                    keptFigure = true;
                    due = due.add(charged(sheet, claim, kept));
                } else if (!claim.isBound()) {
                    // a claim priced at the grade in force on its date keeps a figure too
                    BigDecimal live = inForce(sheetRates, person.gradeOn(claim.getDate()), claim.getDate())
                            .map(rate -> rated(sheet, claim, sheetClaims, rate))
                            .map(d -> floored(sheet, claim, d))
                            .orElse(null);
                    if (live != null) {
                        keptFigure = true;
                        due = due.add(charged(sheet, claim, live));
                    }
                }
            }
            boolean rated = today != null && inForce(sheetRates, person.gradeOn(today), today).isPresent();
            BigDecimal personDue = rated || keptFigure ? due : null;
            figures.put(person.getId(), new Figures(person.getName(), claimed, personDue));
            claimedTotal = claimedTotal.add(claimed);
            if (personDue != null) {
                dueTotal = dueTotal.add(personDue);
            }
        }
        return new Snapshot(figures, claimedTotal, dueTotal);
    }

    /** The due-total of the claims that count dated from one date to another, both inclusive, as the
     *  screen shows a period's: each claim's settled due, else the figure its statement bound, else its
     *  live due, at least the floor, an overdue claim's late charge on top (a claim with no figure adds
     *  nothing). */
    public BigDecimal dueBetween(String sheetId, LocalDate from, LocalDate to) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAsc(sheetId);
        Map<Long, Person> byId = new LinkedHashMap<>();
        people.findBySheetIdOrderByIdAsc(sheetId).forEach(p -> byId.put(p.getId(), p));
        BigDecimal total = ZERO;
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        for (Claim claim : sheetClaims) {
            if (!countsLive(claim, byId.get(claim.getPersonId())) || claim.getDate().isBefore(from) || claim.getDate().isAfter(to)) {
                continue;
            }
            BigDecimal due;
            if (claim.getSettledDue() != null) {
                due = claim.getSettledDue();
            } else if (claim.isBound()) {
                due = claim.getStatedDue();
            } else {
                Person person = byId.get(claim.getPersonId());
                due = person == null ? null : inForce(sheetRates, person.gradeOn(claim.getDate()), claim.getDate())
                        .map(rate -> rated(sheet, claim, sheetClaims, rate))
                        .map(d -> floored(sheet, claim, d))
                        .orElse(null);
            }
            if (due != null) {
                total = total.add(charged(sheet, claim, due));
            }
        }
        return total;
    }

    /** Record, once the change commits, the lines for every figure that moved since {@code before}. */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        Snapshot after = take(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        after.people().forEach((id, now) -> {
            Figures was = before.people().get(id);
            if (was == null || was.claimed().compareTo(now.claimed()) != 0
                    || !sameDue(was.dueTotal(), now.dueTotal())) {
                audit.recordOnCommit("ROLLUP" + prefix + " person=" + now.name()
                        + " claimed=" + now.claimed().toPlainString() + " due-total=" + show(now.dueTotal()));
            }
        });
        if (before.claimedTotal().compareTo(after.claimedTotal()) != 0
                || before.dueTotal().compareTo(after.dueTotal()) != 0) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + after.claimedTotal().toPlainString()
                    + " due-total=" + after.dueTotal().toPlainString());
        }
    }

    /** Whether a claim counts in the live totals: its status counts, and it is not left out by a hold. */
    private static boolean countsLive(Claim claim, Person person) {
        return claim.getStatus().counts() && !heldOut(claim, person);
    }

    /** Whether a hold leaves a claim out of the live totals: it is OPEN or PENDING, bound by no statement,
     *  and dated within one of its person's holds. */
    public static boolean heldOut(Claim claim, Person person) {
        return person != null && claim.getStatus().isOpen() && !claim.isBound() && person.heldOn(claim.getDate());
    }

    /** A claim's amount at a rate, divided by 100, rounded to cents as the claim's rounding date rounds on
     *  the sheet (half to even with no sheet). */
    private static BigDecimal rated(Optional<Sheet> sheet, Claim claim, List<Claim> sheetClaims, Rate rate) {
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

    /** A live due as it counts: at least the sheet's floor, unless its claim is a correction. */
    private static BigDecimal floored(Optional<Sheet> sheet, Claim claim, BigDecimal due) {
        return claim.isCorrection() ? due : sheet.map(s -> s.floored(due)).orElse(due);
    }

    /** A figure with its late charge on top while its claim is OPEN or PENDING (only those go overdue); a
     *  settled claim's due already holds the charge frozen in when it was settled. A correction has no
     *  late charge. */
    public static BigDecimal charged(Optional<Sheet> sheet, Claim claim, BigDecimal figure) {
        if (!claim.getStatus().isOpen() || claim.isCorrection()) {
            return figure;
        }
        return figure.add(sheet.map(s -> s.lateCharge(figure, claim.getDueBy(), claim.getDate())).orElse(ZERO));
    }

    private static boolean sameDue(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? Objects.equals(a, b) : a.compareTo(b) == 0;
    }

    private static String show(BigDecimal due) {
        return due == null ? "n/a" : due.toPlainString();
    }

    /** The grade's rate in force on a date: the latest from-date on or before it, none first; none
     *  when the date is after that rate's to-date. */
    private static Optional<Rate> inForce(List<Rate> sheetRates, String grade, LocalDate date) {
        return sheetRates.stream()
                .filter(r -> r.getGrade().equals(grade))
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.coversThrough(date));
    }
}
