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
 * its figure plus the charges of the boundaries before it (the charge compounds), rounded to the cent as the claim rounds (see {@link Rounding}), the charges added up. A correction has none, nor has a claim whose charge was waived. A statement binds the due as shown, charge included (see StatementService); settling a claim fixes it into its due.
 * The floor is applied first: the figure is raised to the floor in force on the claim's date (a correction has no
 * floor), and the charge is worked out on that floored figure (or the floored figure a statement bound); a claim's
 * live due is its floored figure plus its charge.
 */
public final class LateCharge {

    /** The days overdue between boundaries that each add a late charge. */
    private static final int PERIOD_DAYS = 30;

    private LateCharge() {
    }

    /** The figure raised to the floor, with its late charge on that added on top; null when the figure is null. */
    public static BigDecimal withCharge(Sheet sheet, Claim claim, String grade, BigDecimal figure) {
        BigDecimal base = floored(sheet, claim, figure);
        return base == null ? null : base.add(charge(sheet, claim, grade, base));
    }

    /**
     * The figure raised to the floor, with its late charge on that on the sheet's today added on top,
     * the claim open ({@code open}: OPEN or PENDING and not deferred) or not; null when the figure is null.
     */
    public static BigDecimal withCharge(Sheet sheet, Claim claim, String grade, BigDecimal figure, boolean open) {
        BigDecimal base = floored(sheet, claim, figure);
        return base == null ? null : base.add(charge(sheet, claim, grade, base, open));
    }

    /**
     * The figure raised to the floor, with the late charge it would have on the sheet's today were the
     * claim open, whatever its status; null when the figure is null.
     */
    public static BigDecimal withChargeAsOpen(Sheet sheet, Claim claim, String grade, BigDecimal figure) {
        BigDecimal base = floored(sheet, claim, figure);
        return base == null ? null : base.add(charge(sheet, claim, grade, base, true));
    }

    /** A figure raised to the floor in force on the claim's date; a correction's is not; null when it is null. */
    public static BigDecimal floored(Sheet sheet, Claim claim, BigDecimal figure) {
        return figure == null || claim.isCorrection() ? figure : figure.max(sheet.floorOn(claim.getDate()));
    }

    /** The late charge on a claim's figure on the sheet's today; zero when it is not overdue. */
    public static BigDecimal charge(Sheet sheet, Claim claim, String grade, BigDecimal figure) {
        return charge(sheet, claim, grade, figure, claim.getStatus().isOpen() && !claim.isDeferredOn(sheet.getToday()));
    }

    private static BigDecimal charge(Sheet sheet, Claim claim, String grade, BigDecimal figure, boolean open) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (figure == null || !open || claim.isCorrection() || claim.isWaived()) {
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
            total = total.add(figure.add(total).multiply(sheet.latePctOn(boundary)).divide(BigDecimal.valueOf(100), 2, mode));
        }
        return total;
    }
}
