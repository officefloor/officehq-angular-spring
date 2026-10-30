package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.sheet.DatedSettings;

/**
 * Every rate on a sheet, the sheet's today, its dated settings (the floor in force on a claim's date, the late
 * percent in force on each 30-day boundary past its due-by, the cap in force on the sheet's today) and its policy date (null: the sheet's today): which rate is in force for a grade on a date, the live due it
 * makes of an amount, the late charge an overdue figure gains, how a claim's figures round, and the most a
 * person's due-total comes to.
 */
public record RateTable(List<Rate> rates, LocalDate today, DatedSettings settings, LocalDate policyDate) {

    /** The days in each period overdue that adds the late percent once more. */
    private static final int LATE_PERIOD_DAYS = 30;

    /**
     * The percent in force for the grade on the date: the rate whose from-date is the latest one on
     * or before it, a rate with no from-date applying from the beginning of time; null when none does,
     * or when that rate's to-date is before the date (it has ended, and no later rate is in force).
     */
    public BigDecimal on(String grade, LocalDate date) {
        return latest(grade, date).filter(r -> !ended(r, date)).map(Rate::getPct).orElse(null);
    }

    /** Whether the grade has no rate on the date only because the rate that would apply has ended by then. */
    public boolean ended(String grade, LocalDate date) {
        return latest(grade, date).filter(r -> ended(r, date)).isPresent();
    }

    /** The grade's rate with the latest from-date on or before the date, ended or not. */
    private Optional<Rate> latest(String grade, LocalDate date) {
        return rates.stream()
                .filter(r -> r.getGrade().equals(grade))
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private static boolean ended(Rate rate, LocalDate date) {
        return rate.getToDate() != null && rate.getToDate().isBefore(date);
    }

    /** The cap in force on the sheet's today: the most a person's due-total comes to; null when there is none. */
    public BigDecimal cap() {
        return settings.cap(today);
    }

    /** A due-total held to the cap: the cap when the sum is above it; as it is otherwise, or when n/a (null). */
    public BigDecimal capped(BigDecimal sum) {
        BigDecimal cap = cap();
        return sum == null || cap == null || sum.compareTo(cap) <= 0 ? sum : cap;
    }

    /** What a due-total's sum is above the cap: 0.00 when it is not, or when n/a (null). */
    public BigDecimal excess(BigDecimal sum) {
        BigDecimal cap = cap();
        return sum == null || cap == null || sum.compareTo(cap) <= 0 ? BigDecimal.ZERO : sum.subtract(cap);
    }

    /** The percent in force for the grade on the sheet's today; null when none is. */
    public BigDecimal current(String grade) {
        return on(grade, today);
    }

    /**
     * How the figures of a claim whose rounding goes by the date round: half up when it is before the
     * policy date (the sheet's today when none is set), half to even on or after it.
     */
    public RoundingMode rounding(LocalDate roundsBy) {
        LocalDate policy = policyDate == null ? today : policyDate;
        return roundsBy.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /**
     * The live due of an amount dated on the date for the grade: the amount at the rate in force then,
     * rounded by the date, but at least the floor in force on the date; null when no rate is.
     */
    public BigDecimal due(BigDecimal amount, String grade, LocalDate date) {
        BigDecimal due = RateService.due(amount, on(grade, date), rounding(date));
        BigDecimal floor = settings.floor(date);
        return due == null || floor == null || due.compareTo(floor) >= 0 ? due : floor;
    }

    /**
     * The late charge on a figure of a claim due by the due-by: for every full 30 days from its due-by to the
     * sheet's today, the late percent in force on that boundary date (the due-by plus 30, 60, 90 ... days) of
     * the figure, each rounded by the date its claim's rounding goes by, added up; a boundary whose percent is
     * 0.00 adds nothing. 0.00 when not a full 30 days overdue.
     */
    public BigDecimal lateCharge(BigDecimal figure, LocalDate dueBy, LocalDate roundsBy) {
        long periods = dueBy.isBefore(today) ? ChronoUnit.DAYS.between(dueBy, today) / LATE_PERIOD_DAYS : 0;
        RoundingMode rounding = rounding(roundsBy);
        BigDecimal charge = BigDecimal.ZERO.setScale(2);
        for (long n = 1; n <= periods; n++) {
            BigDecimal pct = settings.latePct(dueBy.plusDays(n * LATE_PERIOD_DAYS));
            if (pct.signum() != 0) {
                charge = charge.add(RateService.due(figure, pct, rounding));
            }
        }
        return charge;
    }
}
