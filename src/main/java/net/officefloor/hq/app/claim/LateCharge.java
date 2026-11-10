package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import net.officefloor.hq.app.sheet.Sheet;

/**
 * The late charge on an overdue claim (OPEN or PENDING, and past its due-by on the sheet's today): for each full
 * 30 days it is overdue, the late percent in force on that boundary date (its due-by plus 30, 60, 90 ... days) of
 * its figure, rounded to the cent as the claim rounds (see {@link Rounding}), the charges added up. A correction has none. Never bound by a statement; settling a claim fixes it into its due.
 */
public final class LateCharge {

    /** The days overdue between boundaries that each add a late charge. */
    private static final int PERIOD_DAYS = 30;

    private LateCharge() {
    }

    /** The figure with its late charge added on top; null when the figure is null. */
    public static BigDecimal withCharge(Sheet sheet, Claim claim, BigDecimal figure) {
        return figure == null ? null : figure.add(charge(sheet, claim, figure));
    }

    /**
     * The figure with the late charge it would have on the sheet's today were the claim open,
     * whatever its status; null when the figure is null.
     */
    public static BigDecimal withChargeAsOpen(Sheet sheet, Claim claim, BigDecimal figure) {
        return figure == null ? null : figure.add(charge(sheet, claim, figure, true));
    }

    /** The late charge on a claim's figure on the sheet's today; zero when it is not overdue. */
    public static BigDecimal charge(Sheet sheet, Claim claim, BigDecimal figure) {
        return charge(sheet, claim, figure, claim.getStatus().isOpen() && !claim.isDeferredOn(sheet.getToday()));
    }

    private static BigDecimal charge(Sheet sheet, Claim claim, BigDecimal figure, boolean open) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (figure == null || !open || claim.isCorrection()) {
            return zero;
        }
        long periods = ChronoUnit.DAYS.between(claim.getDueBy(), sheet.getToday()) / PERIOD_DAYS;
        if (periods <= 0) {
            return zero;
        }
        RoundingMode mode = Rounding.mode(sheet, claim.getDate());
        BigDecimal total = zero;
        for (long k = 1; k <= periods; k++) {
            LocalDate boundary = claim.getDueBy().plusDays(k * PERIOD_DAYS);
            total = total.add(figure.multiply(sheet.latePctOn(boundary)).divide(BigDecimal.valueOf(100), 2, mode));
        }
        return total;
    }
}
