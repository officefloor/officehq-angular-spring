package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The late charge on a claim: an open claim past its due-by (fixed when it was added) gains
 * the sheet's late percent of its figure for every full 30 days it is overdue, rounded as the claim's
 * {@link Rounding} decides, like due; a correction gains none. A statement never binds the charge; it follows the sheet's today until the claim is settled.
 */
public final class LateCharge {

    /** The days a claim must be overdue for each step of the late charge. */
    public static final int DAYS_PER_CHARGE = 30;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private LateCharge() {
    }

    /** The late charge on the claim of the figure (null: n/a, and no charge) on the sheet's today. */
    public static BigDecimal of(Claim claim, BigDecimal figure, LocalDate today, BigDecimal latePct,
            RoundingMode rounding) {
        return claim.getStatus().isOpen() ? asOpen(claim, figure, today, latePct, rounding) : ZERO;
    }

    /** The late charge the claim of the figure would have on the sheet's today were it open. */
    private static BigDecimal asOpen(Claim claim, BigDecimal figure, LocalDate today, BigDecimal latePct,
            RoundingMode rounding) {
        if (figure == null || today == null || claim.isCorrection()) return ZERO;
        long overdueDays = ChronoUnit.DAYS.between(claim.getDueBy(), today);
        long steps = overdueDays <= 0 ? 0 : overdueDays / DAYS_PER_CHARGE;
        if (steps == 0) return ZERO;
        return figure.multiply(latePct).multiply(BigDecimal.valueOf(steps))
                .divide(BigDecimal.valueOf(100), 2, rounding);
    }

    /** The figure with its late charge added on top; null when the figure is n/a. */
    public static BigDecimal dueOf(Claim claim, BigDecimal figure, LocalDate today, BigDecimal latePct,
            RoundingMode rounding) {
        return figure == null ? null : figure.add(of(claim, figure, today, latePct, rounding));
    }

    /** The figure with the late charge it would have were the claim open added on top; null when the figure is n/a. */
    public static BigDecimal dueAsOpen(Claim claim, BigDecimal figure, LocalDate today, BigDecimal latePct,
            RoundingMode rounding) {
        return figure == null ? null : figure.add(asOpen(claim, figure, today, latePct, rounding));
    }
}
