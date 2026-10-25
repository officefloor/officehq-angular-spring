package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The late charge on a claim: an open claim past its due-by (its date plus the sheet's terms) gains
 * the sheet's late percent of its figure for every full 30 days it is overdue, rounded half to even
 * like due; a correction gains none. A statement never binds the charge; it follows the sheet's today until the claim is settled.
 */
public final class LateCharge {

    /** The days a claim must be overdue for each step of the late charge. */
    public static final int DAYS_PER_CHARGE = 30;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private LateCharge() {
    }

    /** The late charge on the claim of the figure (null: n/a, and no charge) on the sheet's today. */
    public static BigDecimal of(Claim claim, BigDecimal figure, int terms, LocalDate today, BigDecimal latePct) {
        if (figure == null || today == null || claim.getStatus() != ClaimStatus.OPEN || claim.isCorrection()) return ZERO;
        long overdueDays = ChronoUnit.DAYS.between(claim.getDate().plusDays(terms), today);
        long steps = overdueDays <= 0 ? 0 : overdueDays / DAYS_PER_CHARGE;
        if (steps == 0) return ZERO;
        return figure.multiply(latePct).multiply(BigDecimal.valueOf(steps))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
    }

    /** The figure with its late charge added on top; null when the figure is n/a. */
    public static BigDecimal dueOf(Claim claim, BigDecimal figure, int terms, LocalDate today, BigDecimal latePct) {
        return figure == null ? null : figure.add(of(claim, figure, terms, today, latePct));
    }
}
