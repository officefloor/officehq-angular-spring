package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Every rate on a sheet, and the sheet's today: which rate is in force for a grade on a date. */
public record RateTable(List<Rate> rates, LocalDate today) {

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
}
