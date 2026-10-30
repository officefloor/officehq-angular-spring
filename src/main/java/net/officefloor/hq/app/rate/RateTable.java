package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Every rate on a sheet, the sheet's today, its floor, its late percent and its policy date (null: the
 * sheet's today): which rate is in force for a grade on a date, the live due it makes of an amount, the
 * late charge an overdue figure gains, and how a claim's figures round.
 */
public record RateTable(List<Rate> rates, LocalDate today, BigDecimal floor, BigDecimal latePct,
        LocalDate policyDate) {

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
     * rounded by the date, but at least the floor; null when no rate is.
     */
    public BigDecimal due(BigDecimal amount, String grade, LocalDate date) {
        BigDecimal due = RateService.due(amount, on(grade, date), rounding(date));
        return due == null || floor == null || due.compareTo(floor) >= 0 ? due : floor;
    }

    /**
     * The late charge on a figure due by the date: the late percent of the figure for every full 30 days
     * from that date to the sheet's today, rounded by the date its claim's rounding goes by; 0.00 when not
     * a full 30 days overdue.
     */
    public BigDecimal lateCharge(BigDecimal figure, LocalDate dueBy, LocalDate roundsBy) {
        long periods = dueBy.isBefore(today) ? ChronoUnit.DAYS.between(dueBy, today) / LATE_PERIOD_DAYS : 0;
        BigDecimal pct = latePct == null ? BigDecimal.ZERO : latePct;
        return RateService.due(figure.multiply(BigDecimal.valueOf(periods)), pct, rounding(roundsBy));
    }
}
