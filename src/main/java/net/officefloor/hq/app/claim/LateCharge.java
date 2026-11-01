package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.function.Function;

/**
 * The late charge on an overdue claim, worked out per 30-day boundary: for each full 30 days past its
 * due-by, the late percent in force on that boundary date (the due-by plus 30, 60, 90 ... days) of its
 * figure (the due bound by a statement, else its floored live due), rounded to cents like its due
 * ({@link Rounding}), and the charges added up; a boundary whose percent is 0.00 adds nothing. A
 * correction has none. It is never bound by a statement; settling freezes it into the settled due.
 */
public final class LateCharge {

    /** The days in each step of the charge. */
    private static final int STEP_DAYS = 30;

    private LateCharge() {}

    /** Overdue: OPEN (or PENDING), not deferred, with its due-by (fixed when it was added) before the sheet's today. */
    public static boolean isOverdue(Claim claim, LocalDate today) {
        return claim.getStatus().isOpen() && today != null && claim.getDueBy().isBefore(today)
                && !claim.isDeferredOn(today);
    }

    /**
     * The charge on the claim with the given figure (null: none) as of today, with the late percent in
     * force on each date from {@code latePctOn}; zero when not overdue or a correction.
     */
    public static BigDecimal of(Claim claim, BigDecimal figure, LocalDate today,
            Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        if (figure == null || claim.isCorrection() || !isOverdue(claim, today)) return BigDecimal.ZERO.setScale(2);
        return charge(claim, figure, today, latePctOn, rounding);
    }

    /**
     * The figure with the charge it would have on top were the claim open now, whatever its status; null
     * when the figure is.
     */
    public static BigDecimal withChargeAsOpen(Claim claim, BigDecimal figure, LocalDate today,
            Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        if (figure == null || claim.isCorrection() || today == null || !claim.getDueBy().isBefore(today)) {
            return figure;
        }
        return figure.add(charge(claim, figure, today, latePctOn, rounding));
    }

    /** The figure with its charge added on top; null when the figure is. */
    public static BigDecimal withCharge(Claim claim, BigDecimal figure, LocalDate today,
            Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        return figure == null ? null : figure.add(of(claim, figure, today, latePctOn, rounding));
    }

    /** The charges of each 30-day boundary past the due-by up to today, added up. */
    private static BigDecimal charge(Claim claim, BigDecimal figure, LocalDate today,
            Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (LocalDate boundary = claim.getDueBy().plusDays(STEP_DAYS); !boundary.isAfter(today);
                boundary = boundary.plusDays(STEP_DAYS)) {
            BigDecimal pct = latePctOn.apply(boundary);
            if (pct == null || pct.signum() == 0) continue;
            total = total.add(figure.multiply(pct).movePointLeft(2).setScale(2, rounding));
        }
        return total;
    }
}
