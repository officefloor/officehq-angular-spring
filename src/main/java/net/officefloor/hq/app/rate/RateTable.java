package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Every rate on a sheet, the sheet's today and its floor: which rate is in force for a grade on a date,
 * and the live due it makes of an amount.
 */
public record RateTable(List<Rate> rates, LocalDate today, BigDecimal floor) {

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
}
