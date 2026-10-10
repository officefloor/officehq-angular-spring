package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The revenue billed in two periods side by side, in the home currency, with how much the second period's revenue
 * changed from the first's (and by what whole percentage, null when nothing was billed in the first period).
 */
public record RevenueComparisonResponse(String homeCurrency, Period a, Period b, BigDecimal change,
        Long changePercent) {

    /** The revenue billed on invoices issued on or between two dates. */
    public record Period(LocalDate from, LocalDate to, long invoices, BigDecimal total) {
    }
}
