package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import net.officefloor.hq.app.sheet.Sheet;

/**
 * The late charge on an overdue claim (OPEN or PENDING, and past its due-by plus the grace days of its person's grade
 * on its date, see {@link Sheet#lateFrom}, on the sheet's today): for each full 30 days it is overdue, the late
 * percent in force on that boundary date (its due-by plus the grace, plus 30, 60, 90 ... days) of
 * its figure, rounded to the cent as the claim rounds (see {@link Rounding}), the charges added up. A correction has none. Never bound by a statement; settling a claim fixes it into its due.
 * The charge is worked out on the rate figure alone (or the figure a statement bound); the floor is applied last: a
 * claim's live due is the greater of the floor in force on its date and its figure plus its charge (a correction has
 * no floor).
 */
public final class LateCharge {

    /** The days overdue between boundaries that each add a late charge. */
    private static final int PERIOD_DAYS = 30;

    private LateCharge() {
    }

    /** The figure with its late charge added on top, then raised to the floor; null when the figure is null. */
    public static BigDecimal withCharge(Sheet sheet, Claim claim, String grade, BigDecimal figure) {
        return figure == null ? null : floored(sheet, claim, figure.add(charge(sheet, claim, grade, figure)));
    }

    /**
     * The figure with its late charge on the sheet's today added on top, then raised to the floor,
     * the claim open ({@code open}: OPEN or PENDING and not deferred) or not; null when the figure is null.
     */
    public static BigDecimal withCharge(Sheet sheet, Claim claim, String grade, BigDecimal figure, boolean open) {
        return figure == null ? null : floored(sheet, claim, figure.add(charge(sheet, claim, grade, figure, open)));
    }

    /**
     * The figure with the late charge it would have on the sheet's today were the claim open,
     * whatever its status; null when the figure is null.
     */
    public static BigDecimal withChargeAsOpen(Sheet sheet, Claim claim, String grade, BigDecimal figure) {
        return figure == null ? null : floored(sheet, claim, figure.add(charge(sheet, claim, grade, figure, true)));
    }

    /** A due raised to the floor in force on the claim's date; a correction's is not. */
    private static BigDecimal floored(Sheet sheet, Claim claim, BigDecimal due) {
        return claim.isCorrection() ? due : due.max(sheet.floorOn(claim.getDate()));
    }

    /** The late charge on a claim's figure on the sheet's today; zero when it is not overdue. */
    public static BigDecimal charge(Sheet sheet, Claim claim, String grade, BigDecimal figure) {
        return charge(sheet, claim, grade, figure, claim.getStatus().isOpen() && !claim.isDeferredOn(sheet.getToday()));
    }

    private static BigDecimal charge(Sheet sheet, Claim claim, String grade, BigDecimal figure, boolean open) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (figure == null || !open || claim.isCorrection()) {
            return zero;
        }
        LocalDate lateFrom = sheet.lateFrom(claim.getDueBy(), claim.getDate(), grade);
        long periods = ChronoUnit.DAYS.between(lateFrom, sheet.getToday()) / PERIOD_DAYS;
        if (periods <= 0) {
            return zero;
        }
        RoundingMode mode = Rounding.mode(sheet, claim.getDate());
        BigDecimal total = zero;
        for (long k = 1; k <= periods; k++) {
            LocalDate boundary = lateFrom.plusDays(k * PERIOD_DAYS);
            total = total.add(figure.multiply(sheet.latePctOn(boundary)).divide(BigDecimal.valueOf(100), 2, mode));
        }
        return total;
    }
}
