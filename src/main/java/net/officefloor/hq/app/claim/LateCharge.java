package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.sheet.Sheet;

/**
 * The sheet's late charge: an OPEN (or PENDING) claim overdue (its late-from, its due-by, fixed when it was created,
 * plus the grace days in force on its date of its person's grade then, before the sheet's today) gains, for every full
 * 30 days it is overdue, the late percent in force on that boundary date (its late-from plus 30, 60, 90 ... days) of
 * its figure plus the charges of the boundaries before it (compounding), each rounded as the claim's rounding policy
 * has it, added up on top; a correction (a negative amount) gains none. It is never bound by a statement; settling freezes it in.
 */
public record LateCharge(LocalDate today, Function<LocalDate, BigDecimal> pctOn,
        BiFunction<String, LocalDate, Integer> graceOn) {

    /** The days in each step of the charge. */
    private static final int STEP_DAYS = 30;

    /** The late charge of a sheet as it is now, at the late percent in force on each boundary date;
     *  a sheet never loaded charges nothing. */
    public static LateCharge of(Optional<Sheet> sheet) {
        return new LateCharge(sheet.map(Sheet::getToday).orElseGet(LocalDate::now),
                on -> sheet.map(s -> s.latePctOn(on)).orElse(Sheet.DEFAULT_LATE_PCT),
                (grade, on) -> sheet.map(s -> s.graceOn(grade, on)).orElse(0));
    }

    /** The date the claim is overdue after: its due-by plus the grace days in force on its date of
     *  its person's grade then (none: its due-by). */
    public LocalDate lateFrom(Claim claim, Person person) {
        return claim.getDueBy().plusDays(graceOn.apply(person.gradeOn(claim.getDate()), claim.getDate()));
    }

    /** Whether the claim's late-from is before the sheet's today (whatever its status). */
    public boolean isPast(Claim claim, Person person) {
        return lateFrom(claim, person).isBefore(today);
    }

    /** The claim's figure (null: n/a, which stays n/a) with its late charge, rounded by {@code mode}, added. */
    public BigDecimal addTo(Claim claim, Person person, BigDecimal figure, RoundingMode mode) {
        if (!claim.getStatus().isOpen() || claim.isDeferredOn(today)) {
            return figure;
        }
        return addToOpen(claim, person, figure, mode);
    }

    /** The figure with the late charge the claim would gain were it open, whatever its status. */
    public BigDecimal addToOpen(Claim claim, Person person, BigDecimal figure, RoundingMode mode) {
        if (figure == null || claim.isCorrection()) {
            return figure;
        }
        LocalDate lateFrom = lateFrom(claim, person);
        long overdue = ChronoUnit.DAYS.between(lateFrom, today);
        if (overdue <= 0) {
            return figure;
        }
        BigDecimal charged = figure;
        for (long step = 1; step <= overdue / STEP_DAYS; step++) {
            BigDecimal pct = pctOn.apply(lateFrom.plusDays(step * STEP_DAYS));
            if (pct.signum() != 0) {
                charged = charged.add(charged.multiply(pct).divide(BigDecimal.valueOf(100), 2, mode));
            }
        }
        return charged;
    }
}
