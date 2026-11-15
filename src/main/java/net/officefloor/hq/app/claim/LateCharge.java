package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Function;
import net.officefloor.hq.app.sheet.Sheet;

/**
 * The sheet's late charge: an OPEN (or PENDING) claim overdue (its due-by, fixed when it was created, before the
 * sheet's today) gains, for every full 30 days it is overdue, the late percent in force on that boundary date (its
 * due-by plus 30, 60, 90 ... days) of its figure, each rounded as the claim's rounding policy has it, added up on top; a correction (a negative amount) gains none. It is never bound by a statement; settling freezes it in.
 */
public record LateCharge(LocalDate today, Function<LocalDate, BigDecimal> pctOn) {

    /** The days in each step of the charge. */
    private static final int STEP_DAYS = 30;

    /** The late charge of a sheet as it is now, at the late percent in force on each boundary date;
     *  a sheet never loaded charges nothing. */
    public static LateCharge of(Optional<Sheet> sheet) {
        return new LateCharge(sheet.map(Sheet::getToday).orElseGet(LocalDate::now),
                on -> sheet.map(s -> s.latePctOn(on)).orElse(Sheet.DEFAULT_LATE_PCT));
    }

    /** The claim's figure (null: n/a, which stays n/a) with its late charge, rounded by {@code mode}, added. */
    public BigDecimal addTo(Claim claim, BigDecimal figure, RoundingMode mode) {
        if (!claim.getStatus().isOpen() || claim.isDeferredOn(today)) {
            return figure;
        }
        return addToOpen(claim, figure, mode);
    }

    /** The figure with the late charge the claim would gain were it open, whatever its status. */
    public BigDecimal addToOpen(Claim claim, BigDecimal figure, RoundingMode mode) {
        if (figure == null || claim.isCorrection()) {
            return figure;
        }
        long overdue = ChronoUnit.DAYS.between(claim.getDueBy(), today);
        if (overdue <= 0) {
            return figure;
        }
        BigDecimal charged = figure;
        for (long step = 1; step <= overdue / STEP_DAYS; step++) {
            BigDecimal pct = pctOn.apply(claim.getDueBy().plusDays(step * STEP_DAYS));
            if (pct.signum() != 0) {
                charged = charged.add(figure.multiply(pct).divide(BigDecimal.valueOf(100), 2, mode));
            }
        }
        return charged;
    }
}
