package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;

/**
 * The late charge on a claim: an open claim past its due-by (fixed when it was added) gains, for every
 * full 30 days it is overdue, the sheet's late percent in force on that boundary (the due-by plus 30, 60,
 * 90 ... days) of its figure, each rounded as the claim's {@link Rounding} decides, like due, and added up;
 * a boundary whose percent is 0.00 adds nothing, and a correction gains none. The charge is worked out on the rate figure alone (or the
 * figure a statement bound), before the floor, which is applied last. A statement never binds the charge; it follows the sheet's today until the claim is settled.
 */
public final class LateCharge {

    /** The days a claim must be overdue for each step of the late charge. */
    public static final int DAYS_PER_CHARGE = 30;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private LateCharge() {
    }

    /** The late charge on the claim of the figure (null: n/a, and no charge) on the sheet's today; none
     *  while the claim is deferred, as it is not overdue then. */
    public static BigDecimal of(Claim claim, BigDecimal figure, LocalDate today,
            Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        return claim.getStatus().isOpen() && !claim.isDeferredOn(today) ? asOpen(claim, figure, today, latePctOn, rounding) : ZERO;
    }

    /** The late charge the claim of the figure would have on the sheet's today were it open. */
    private static BigDecimal asOpen(Claim claim, BigDecimal figure, LocalDate today,
            Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        if (figure == null || today == null || claim.isCorrection()) return ZERO;
        long overdueDays = ChronoUnit.DAYS.between(claim.getDueBy(), today);
        long steps = overdueDays <= 0 ? 0 : overdueDays / DAYS_PER_CHARGE;
        BigDecimal charge = ZERO;
        for (long step = 1; step <= steps; step++) {
            BigDecimal pct = latePctOn.apply(claim.getDueBy().plusDays(step * DAYS_PER_CHARGE));
            charge = charge.add(figure.multiply(pct).divide(BigDecimal.valueOf(100), 2, rounding));
        }
        return charge;
    }

    /** The claim's due of the figure (null: n/a): a due fixed when it was settled stays as it is; else the
     *  figure with its late charge on top, and at least the floor, the floor applied last (a correction has
     *  no floor and no charge); null when the figure is n/a. */
    public static BigDecimal dueOf(Claim claim, BigDecimal figure, BigDecimal floor, LocalDate today,
            Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        if (figure == null || claim.getSettledDue() != null) return figure;
        return floored(claim, figure.add(of(claim, figure, today, latePctOn, rounding)), floor);
    }

    /** The claim's due of the figure as it would be were the claim open: the figure with the late charge it
     *  would have on top, and at least the floor, applied last; null when the figure is n/a. */
    public static BigDecimal dueAsOpen(Claim claim, BigDecimal figure, BigDecimal floor, LocalDate today,
            Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        return figure == null ? null : floored(claim, figure.add(asOpen(claim, figure, today, latePctOn, rounding)), floor);
    }

    /** The due held to at least the floor, unless the claim is a correction, which has no floor. */
    public static BigDecimal floored(Claim claim, BigDecimal due, BigDecimal floor) {
        return claim.isCorrection() ? due : due.max(floor);
    }
}
