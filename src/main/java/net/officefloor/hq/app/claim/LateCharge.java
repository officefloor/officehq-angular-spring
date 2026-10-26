package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;

/**
 * The late charge on a claim: an open claim past its due-by (fixed when it was added) gains, for every
 * full 30 days it is overdue, the sheet's late percent in force on that boundary (the due-by plus 30, 60,
 * 90 ... days, counted from the due-by plus the grace days of its person's grade, if any) of its figure plus the
 * charges of the boundaries before it (compounding), each rounded as the claim's {@link Rounding} decides, like due, and added up;
 * a boundary whose percent is 0.00 adds nothing, and a correction or a waived claim gains none. The floor is applied first:
 * the charge is worked out on the rate figure held to at least the floor. A statement binds the due as shown, floor and
 * charge included: while the claim is bound its due is that figure and the charge no longer follows the sheet's today.
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
            int grace, Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        return claim.getStatus().isOpen() && !claim.isDeferredOn(today) ? asOpen(claim, figure, today, grace, latePctOn, rounding) : ZERO;
    }

    /** The late charge the claim of the figure would have on the sheet's today were it open. */
    private static BigDecimal asOpen(Claim claim, BigDecimal figure, LocalDate today,
            int grace, Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        if (figure == null || today == null || claim.isCorrection() || claim.isWaived()) return ZERO;
        LocalDate lateFrom = lateFrom(claim, grace);
        long overdueDays = ChronoUnit.DAYS.between(lateFrom, today);
        long steps = overdueDays <= 0 ? 0 : overdueDays / DAYS_PER_CHARGE;
        BigDecimal charge = ZERO;
        for (long step = 1; step <= steps; step++) {
            BigDecimal pct = latePctOn.apply(lateFrom.plusDays(step * DAYS_PER_CHARGE));
            charge = charge.add(figure.add(charge).multiply(pct).divide(BigDecimal.valueOf(100), 2, rounding));
        }
        return charge;
    }

    /** The claim's due of the figure (null: n/a): a due fixed when it was settled, or bound by a statement, stays as it is; else the
     *  figure held to at least the floor, with the late charge worked out on that floored figure on top (a
     *  correction has no floor and no charge); null when the figure is n/a. */
    public static BigDecimal dueOf(Claim claim, BigDecimal figure, BigDecimal floor, LocalDate today,
            int grace, Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        if (figure == null || claim.getSettledDue() != null || claim.isBound()) return figure;
        BigDecimal floored = floored(claim, figure, floor);
        return floored.add(of(claim, floored, today, grace, latePctOn, rounding));
    }

    /** The claim's due of the figure as it would be were the claim open: the
     *  figure held to at least the floor, with the late charge it would have on that floored figure on top;
     *  null when the figure is n/a. */
    public static BigDecimal dueAsOpen(Claim claim, BigDecimal figure, BigDecimal floor, LocalDate today,
            int grace, Function<LocalDate, BigDecimal> latePctOn, RoundingMode rounding) {
        if (figure == null) return null;
        BigDecimal floored = floored(claim, figure, floor);
        return floored.add(asOpen(claim, floored, today, grace, latePctOn, rounding));
    }

    /** The date the claim's lateness counts from: its due-by plus the grace days of its person's grade in
     *  force on its date; the due-by shown is unchanged. */
    public static LocalDate lateFrom(Claim claim, int grace) {
        return claim.getDueBy().plusDays(grace);
    }

    /** The due held to at least the floor, unless the claim is a correction, which has no floor. */
    public static BigDecimal floored(Claim claim, BigDecimal due, BigDecimal floor) {
        return claim.isCorrection() ? due : due.max(floor);
    }
}
