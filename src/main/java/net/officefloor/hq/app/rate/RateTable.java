package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * Every rate on a sheet, the sheet's today, its floor, its terms and its late percent: which rate is in
 * force for a grade on a date, the live due it makes of an amount, the date a claim is due by, and the
 * late charge an overdue figure gains.
 */
public record RateTable(List<Rate> rates, LocalDate today, BigDecimal floor, int terms, BigDecimal latePct) {

    /** The days in each period overdue that adds the late percent once more. */
    private static final int LATE_PERIOD_DAYS = 30;

    /**
     * The percent in force for the grade on the date: the rate whose from-date is the latest one on
     * or before it, a rate with no from-date applying from the beginning of time; null when none does.
     */
    public BigDecimal on(String grade, LocalDate date) {
        return rates.stream()
                .filter(r -> r.getGrade().equals(grade))
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(Rate::getPct).orElse(null);
    }

    /** The percent in force for the grade on the sheet's today; null when none is. */
    public BigDecimal current(String grade) {
        return on(grade, today);
    }

    /**
     * The live due of an amount dated on the date for the grade: the amount at the rate in force then,
     * but at least the floor; null when no rate is.
     */
    public BigDecimal due(BigDecimal amount, String grade, LocalDate date) {
        BigDecimal due = RateService.due(amount, on(grade, date));
        return due == null || floor == null || due.compareTo(floor) >= 0 ? due : floor;
    }

    /** The date a claim dated on the date is due by: the date plus the terms. */
    public LocalDate dueBy(LocalDate date) {
        return date.plusDays(terms);
    }

    /**
     * The late charge on a figure due by the date: the late percent of the figure for every full 30 days
     * from that date to the sheet's today, rounded like a due; 0.00 when not a full 30 days overdue.
     */
    public BigDecimal lateCharge(BigDecimal figure, LocalDate dueBy) {
        long periods = dueBy.isBefore(today) ? ChronoUnit.DAYS.between(dueBy, today) / LATE_PERIOD_DAYS : 0;
        BigDecimal pct = latePct == null ? BigDecimal.ZERO : latePct;
        return RateService.due(figure.multiply(BigDecimal.valueOf(periods)), pct);
    }
}
