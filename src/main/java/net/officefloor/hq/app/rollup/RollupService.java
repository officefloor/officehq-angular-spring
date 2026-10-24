package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.settings.DatedSettings;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures each person on a sheet shows — what they have claimed (their OPEN, PENDING and SETTLED
 * claims, leaving out the OPEN and PENDING ones dated within one of their holds that no statement binds)
 * and their due-total (the sum of what is due on those claims as each shows it, n/a when none has a figure and no rate of
 * their grade is in force on the sheet's today, and at most the sheet's cap in force on its today when it has one) and their
 * overdue-total (the sum of what is due on those that are OPEN or PENDING and past their due-by on the
 * sheet's today, as each shows it) — and the sheet's totals of them (the due-total and overdue-total
 * leave out the single highest and lowest person's figure when three or more people have one; a
 * due-total of n/a is no figure). A change that moves
 * any of them is followed in the audit by a ROLLUP line per person whose figures moved, then a TOTALS
 * line when the sheet's totals moved: take a {@link #snapshot} before the change, and
 * {@link #recordSince} it after the change has recorded its own lines.
 */
@Service
public class RollupService {

    /** A due-total shown when there is none. */
    public static final String NONE = "n/a";

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final DatedSettings dated;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, RateRepository rates,
            SheetRepository sheets, DatedSettings dated, Audit audit) {
        this.dated = dated;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** A person's figures, as shown. */
    public record Figures(String name, String claimed, String dueTotal, String overdueTotal) {
    }

    /** The sheet's figures at one moment: each person's, by id in the order they were added, and the totals. */
    public record Snapshot(Map<Long, Figures> people, String claimedTotal, String dueTotal, String overdueTotal) {
    }

    /** The figures the sheet shows now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot snapshot(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        BigDecimal cap = sheet == null ? null : dated.cap(sheetId);
        List<Claim> all = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = zero();
        List<BigDecimal> dueFigures = new ArrayList<>();
        List<BigDecimal> overdueFigures = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            BigDecimal overdue = zero();
            boolean any = false;
            for (Claim claim : all) {
                if (!claim.getPersonId().equals(person.getId()) || !countsLive(claim, person, today)) continue;
                claimed = claimed.add(claim.getAmount());
                BigDecimal figure = shownOf(claim, person);
                if (figure == null) continue;
                if (isOverdue(claim, today)) overdue = overdue.add(figure);
                due = due.add(figure);
                any = true;
            }
            due = cappedOf(due, cap);
            boolean rated = today != null && rates.findInForce(sheetId, person.gradeOn(today), today).isPresent();
            claimedTotal = claimedTotal.add(claimed);
            overdueFigures.add(overdue);
            if (any || rated) dueFigures.add(due);
            figures.put(person.getId(), new Figures(person.getName(), text(claimed), any || rated ? text(due) : NONE,
                    text(overdue)));
        }
        return new Snapshot(figures, text(claimedTotal), text(trimmedSum(dueFigures)), text(trimmedSum(overdueFigures)));
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
    static BigDecimal trimmedSum(List<BigDecimal> figures) {
        BigDecimal sum = figures.stream().reduce(zero(), BigDecimal::add);
        if (figures.size() < 3) return sum;
        return sum.subtract(Collections.max(figures)).subtract(Collections.min(figures));
    }

    /** Record the ROLLUP and TOTALS lines for the figures that moved since {@code before}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSince(String sheetId, String user, Snapshot before) {
        Snapshot after = snapshot(sheetId);
        after.people().forEach((id, now) -> {
            Figures then = before.people().get(id);
            if (then != null && then.claimed().equals(now.claimed()) && then.dueTotal().equals(now.dueTotal())
                    && then.overdueTotal().equals(now.overdueTotal())) return;
            audit.change("ROLLUP", sheetId, user, "person=" + now.name(), "claimed=" + now.claimed(),
                    "due-total=" + now.dueTotal(), "overdue-total=" + now.overdueTotal());
        });
        if (!Objects.equals(before.claimedTotal(), after.claimedTotal())
                || !Objects.equals(before.dueTotal(), after.dueTotal())
                || !Objects.equals(before.overdueTotal(), after.overdueTotal())) {
            audit.change("TOTALS", sheetId, user, "claimed-total=" + after.claimedTotal(),
                    "due-total=" + after.dueTotal(), "overdue-total=" + after.overdueTotal());
        }
    }

    /** Whether the claim is overdue: OPEN or PENDING, not deferred on the sheet's {@code today}, and due
     * by a date before it. */
    public static boolean isOverdue(Claim claim, LocalDate today) {
        return claim.getStatus().isOpen() && today != null && claim.getDueBy() != null
                && !claim.isDeferredOn(today) && claim.getDueBy().isBefore(today);
    }

    /** A due-total as shown: at most {@code cap}, when there is one. */
    public static BigDecimal cappedOf(BigDecimal due, BigDecimal cap) {
        return cap == null ? due : due.min(cap);
    }

    /** Whether the claim counts in the live totals on the sheet's {@code today}: OPEN, PENDING or
     * SETTLED, but not an OPEN claim deferred on {@code today}, nor OPEN or PENDING when it is dated
     * within one of its person's holds and no statement binds it (a PENDING claim is bound by none while
     * it is). */
    public static boolean countsLive(Claim claim, Person person, LocalDate today) {
        return countsAs(claim, claim.getStatus(), person, today);
    }

    /** Whether the claim would count in the live totals on the sheet's {@code today} were its status
     * {@code status} (see {@link #countsLive}). */
    public static boolean countsAs(Claim claim, ClaimStatus status, Person person, LocalDate today) {
        if (!status.counts()) return false;
        if (!status.isOpen()) return true;
        if (claim.isDeferredOn(today)) return false;
        boolean bound = claim.isStated() && status != ClaimStatus.PENDING;
        return bound || !person.isHeldOn(claim.getDate());
    }

    /** Whether the claim shows the figure fixed when it was settled at a rate: it is SETTLED, or was
     * voided once settled, and has one. */
    private static boolean frozen(Claim claim) {
        return claim.getSettledDue() != null
                && (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID);
    }

    /** The figure the claim shows and totals now: what was fixed when it was settled at a rate (kept
     * once it is voided);
     * otherwise, unless it is PENDING (which no statement binds while it is), the figure a statement
     * bound it to; otherwise what is due on it at its person's rate.
     * Null when it has no figure. */
    public BigDecimal figureOf(Claim claim, Person person) {
        if (frozen(claim)) return claim.getSettledDue();
        if (claim.isStated() && claim.getStatus() != ClaimStatus.PENDING) return claim.getStatedDue();
        return dueOf(claim, person);
    }

    /** What is due on the claim as it shows and totals now: what was fixed when it was settled at a
     * rate; otherwise its live due on its figure (see {@link #liveDueOf}); null when it has no figure. */
    public BigDecimal shownOf(Claim claim, Person person) {
        BigDecimal figure = figureOf(claim, person);
        if (frozen(claim)) return figure;
        return liveDueOf(claim, figure);
    }

    /** The live due on the claim whose figure (at its rate, or as a statement bound it) is
     * {@code figure}: the greater of the sheet's floor in force on the claim's date and that figure plus
     * its late charge (see {@link #lateChargeOf}); a correction has neither floor nor charge. Null when
     * {@code figure} is. */
    public BigDecimal liveDueOf(Claim claim, BigDecimal figure) {
        if (figure == null) return null;
        if (claim.isCorrection()) return figure;
        return floored(claim, figure.add(lateChargeOf(claim, figure)));
    }

    /** {@code due}, at least the sheet's floor in force on the claim's date. */
    private BigDecimal floored(Claim claim, BigDecimal due) {
        Sheet sheet = sheets.findById(claim.getSheetId()).orElse(null);
        BigDecimal floor = sheet == null ? zero() : dated.floorOn(sheet.getId(), claim.getDate());
        return due.max(floor);
    }

    /** The late charge on an OPEN or PENDING claim whose figure is {@code figure}: for every full 30 days the claim is overdue (past its due-by, fixed when it was added, on
     * the sheet's today), the sheet's late percent in force on that boundary date (the due-by plus 30, 60,
     * 90 ... days) of that figure, rounded to two decimals as the claim rounds, the charges added up (a boundary whose percent is 0.00 adds nothing) (see {@link #roundingOf}); 0.00 on a claim not OPEN or
     * PENDING, or not overdue (a deferred claim is not), and on a correction. A statement never binds it: it follows the sheet's today until the claim is settled. */
    public BigDecimal lateChargeOf(Claim claim, BigDecimal figure) {
        if (!claim.getStatus().isOpen()) return zero();
        return overdueChargeOf(claim, figure);
    }

    /** What would be due on the claim now were it an OPEN claim bound by no statement: its due at its
     * person's rate (see {@link #dueOf}) plus the late charge it would have then, at least the sheet's
     * floor in force on its date (a correction has neither); null when it would have no figure. */
    public BigDecimal openShownOf(Claim claim, Person person) {
        BigDecimal figure = dueOf(claim, person);
        if (figure == null || claim.isCorrection()) return figure;
        return floored(claim, figure.add(overdueChargeOf(claim, figure)));
    }

    /** The late charge on a claim whose figure is {@code figure}, whatever its status (see {@link #lateChargeOf}). */
    private BigDecimal overdueChargeOf(Claim claim, BigDecimal figure) {
        if (claim.isCorrection()) return zero();
        Sheet sheet = sheets.findById(claim.getSheetId()).orElse(null);
        if (sheet == null || claim.isDeferredOn(sheet.getToday())) return zero();
        long periods = Math.max(0, ChronoUnit.DAYS.between(claim.getDueBy(), sheet.getToday())) / 30;
        RoundingMode rounding = roundingOf(claim, sheet);
        BigDecimal charge = zero();
        for (long k = 1; k <= periods; k++) {
            BigDecimal pct = dated.latePctOn(sheet.getId(), claim.getDueBy().plusDays(30 * k));
            if (pct.signum() == 0) continue;
            charge = charge.add(figure.multiply(pct).movePointLeft(2).setScale(2, rounding));
        }
        return charge;
    }

    /** What is due on the claim at its person's rate in force on the claim's date: amount times pct,
     * divided by 100, rounded to two decimals as the claim rounds (see {@link #roundingOf}) — the rate
     * figure alone, before any late charge or floor (see {@link #liveDueOf}); null when no
     * rate of the grade in force for them on the claim's date applies then. */
    public BigDecimal dueOf(Claim claim, Person person) {
        Sheet sheet = sheets.findById(claim.getSheetId()).orElse(null);
        RoundingMode rounding = roundingOf(claim, sheet);
        return rates.findInForce(claim.getSheetId(), person.gradeOn(claim.getDate()), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, rounding))
                .orElse(null);
    }

    /** How the claim's live figures round: half up when it is dated before the sheet's policy date in
     * force, half to even when on or after it. A correction rounds by the date of the claim it corrects. */
    public RoundingMode roundingOf(Claim claim, Sheet sheet) {
        if (sheet == null) return RoundingMode.HALF_EVEN;
        LocalDate dated = claim.getDate();
        if (claim.isCorrection() && claim.getCorrects() != null) {
            dated = claims.findBySheetIdAndPersonIdAndNumber(claim.getSheetId(), claim.getPersonId(), claim.getCorrects())
                    .map(Claim::getDate).orElse(dated);
        }
        return dated.isBefore(sheet.policyDateInForce()) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String text(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
