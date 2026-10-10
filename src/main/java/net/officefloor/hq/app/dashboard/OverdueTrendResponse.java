package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * How the overdue total has changed over recent months, in the home currency: one figure per month, oldest first,
 * ending with the current month.
 */
public record OverdueTrendResponse(LocalDate asOf, String homeCurrency, List<Month> months) {

    /**
     * What was overdue as at the close of a month (given as {@code yyyy-MM}), or as at today for the current month,
     * and the change from the month before (null for the first month listed).
     */
    public record Month(String month, LocalDate asOf, BigDecimal amount, BigDecimal change) {
    }
}
