package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Component;

/** What is due on a claim, read from the rates its person's grade has. */
@Component
public class Dues {

    private final RateRepository rates;
    private final SheetRepository sheets;
    private final ClaimRepository claims;

    public Dues(RateRepository rates, SheetRepository sheets, ClaimRepository claims) {
        this.rates = rates;
        this.sheets = sheets;
        this.claims = claims;
    }

    /**
     * How a claim's live figures round: by the sheet's policy date against the claim's date, or for a
     * correction the date of the claim it corrects (its own when that claim is not found).
     */
    private RoundingMode rounding(Claim claim, Sheet sheet) {
        LocalDate date = claim.getDate();
        if (claim.getCorrects() != null) {
            date = claims.findBySheetIdAndPersonIdAndNumber(claim.getSheetId(), claim.getPersonId(), claim.getCorrects())
                    .map(Claim::getDate).orElse(date);
        }
        return sheet == null ? RoundingMode.HALF_EVEN : sheet.roundingOn(date);
    }

    /**
     * The due figure a claim shows: the one fixed when it was settled, else (unless it is PENDING,
     * which is not bound) the one its person's last statement bound it to, else its amount at the rate the grade has in force on the claim's date;
     * null (n/a) when there is none.
     */
    public BigDecimal figure(Claim claim, String grade) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() && claim.getStatus() != ClaimStatus.PENDING ? claim.getStatedDue() : live(claim, grade);
    }

    /**
     * The due a claim shows: its figure (see {@link #figure}) with the late charge on top while it is
     * overdue; a settled claim's figure already holds the charge it was settled with. Null (n/a) when
     * it has no figure.
     */
    public BigDecimal due(Claim claim, String grade) {
        return withLate(claim, figure(claim, grade));
    }

    /**
     * The due a claim is settled at: the figure a statement bound it to, else its live figure, with
     * the late charge it has on the sheet's today on top; null when it has no figure.
     */
    public BigDecimal settling(Claim claim, String grade) {
        return withLate(claim, claim.isStated() ? claim.getStatedDue() : live(claim, grade));
    }

    /**
     * The due a PENDING claim is approved at: its live figure (a statement does not bind it while
     * PENDING) with the late charge it has on the sheet's today on top; null when it has no figure.
     */
    public BigDecimal approving(Claim claim, String grade) {
        return withLate(claim, live(claim, grade));
    }

    /**
     * The due a SETTLED claim is re-settled at: what it would show today as an OPEN claim bound by no
     * statement, its live figure with the late charge it would have on the sheet's today on top; null
     * when it has no figure.
     */
    public BigDecimal resettling(Claim claim, String grade) {
        BigDecimal figure = live(claim, grade);
        return figure == null || claim.isCorrection() ? figure : lateOn(claim, figure);
    }

    /**
     * A figure with the claim's late charge on top while an OPEN or PENDING claim is past its due-by
     * (fixed when it was added) on the sheet's today (see {@link #lateOn}), rounded by the claim's date
     * against the policy date. The charge is never bound: it always follows the sheet's today.
     * A correction has no late charge, nor has a claim deferred on the sheet's today (it is not overdue).
     */
    private BigDecimal withLate(Claim claim, BigDecimal figure) {
        if (figure == null || claim.isCorrection() || !claim.getStatus().isOpen() || claim.getSettledDue() != null) {
            return figure;
        }
        Sheet sheet = sheets.findById(claim.getSheetId()).orElse(null);
        if (sheet != null && claim.isDeferredOn(sheet.getToday())) {
            return figure;
        }
        return lateOn(claim, figure);
    }

    /**
     * A figure with the late charge an OPEN claim would have on the sheet's today on top: for every full
     * 30 days past its due-by, the late percent in force on that boundary date (the due-by plus 30, 60,
     * 90 ... days) of the figure, rounded like the claim, the charges added up.
     */
    private BigDecimal lateOn(Claim claim, BigDecimal figure) {
        Sheet sheet = sheets.findById(claim.getSheetId()).orElse(null);
        if (sheet == null) {
            return figure;
        }
        LocalDate dueBy = claim.getDueBy();
        long periods = ChronoUnit.DAYS.between(dueBy, sheet.getToday()) / 30;
        RoundingMode rounding = rounding(claim, sheet);
        BigDecimal late = BigDecimal.ZERO.setScale(2);
        for (long period = 1; period <= periods; period++) {
            BigDecimal latePct = sheet.latePctOn(dueBy.plusDays(30 * period));
            if (latePct != null && latePct.signum() != 0) {
                late = late.add(figure.multiply(latePct).movePointLeft(2).setScale(2, rounding));
            }
        }
        return figure.add(late);
    }

    /**
     * A claim's amount at the rate the grade has in force on the claim's date, raised to the floor in
     * force on the claim's date when below it (a correction has no floor), rounded by the claim's date against the policy
     * date; null when it has no rate.
     */
    public BigDecimal live(Claim claim, String grade) {
        Sheet sheet = sheets.findById(claim.getSheetId()).orElse(null);
        BigDecimal floor = claim.isCorrection() || sheet == null ? null : sheet.floorOn(claim.getDate());
        RoundingMode rounding = rounding(claim, sheet);
        return rates.findInForce(claim.getSheetId(), grade, claim.getDate())
                .map(rate -> due(claim.getAmount(), rate, rounding))
                .map(due -> floor != null && due.compareTo(floor) < 0 ? floor : due)
                .orElse(null);
    }

    /** What is due on an amount at a rate: the amount times the percent over 100, rounded as given. */
    private static BigDecimal due(BigDecimal amount, Rate rate, RoundingMode rounding) {
        return amount.multiply(rate.getPct()).movePointLeft(2).setScale(2, rounding);
    }
}
