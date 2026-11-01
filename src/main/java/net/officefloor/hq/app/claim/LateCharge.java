package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The late charge on an overdue claim: the sheet's late percent of its figure (the due bound by a
 * statement, else its floored live due) for every full 30 days past its due-by, rounded to cents like
 * its due ({@link Rounding}). A correction has none. It is never bound by a statement; settling freezes it into the settled due.
 */
public final class LateCharge {

    /** The days in each step of the charge. */
    private static final int STEP_DAYS = 30;

    private LateCharge() {}

    /** Overdue: OPEN (or PENDING) with its due-by (fixed when it was added) before the sheet's today. */
    public static boolean isOverdue(Claim claim, LocalDate today) {
        return claim.getStatus().isOpen() && today != null && claim.getDueBy().isBefore(today);
    }

    /** The charge on the claim with the given figure (null: none) as of today; zero when not overdue or a correction. */
    public static BigDecimal of(Claim claim, BigDecimal figure, LocalDate today, BigDecimal latePct,
            RoundingMode rounding) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (figure == null || claim.isCorrection() || !isOverdue(claim, today)) return zero;
        long steps = ChronoUnit.DAYS.between(claim.getDueBy(), today) / STEP_DAYS;
        return figure.multiply(latePct).multiply(BigDecimal.valueOf(steps)).movePointLeft(2)
                .setScale(2, rounding);
    }

    /** The figure with its charge added on top; null when the figure is. */
    public static BigDecimal withCharge(Claim claim, BigDecimal figure, LocalDate today, BigDecimal latePct,
            RoundingMode rounding) {
        return figure == null ? null : figure.add(of(claim, figure, today, latePct, rounding));
    }
}
