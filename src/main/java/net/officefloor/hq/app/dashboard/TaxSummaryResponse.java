package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The tax charged on invoices issued on or between two dates, in the home currency: the sales tax and the levy
 * (second tax) separately, the net of the manual tax adjustments dated within the period, and the total owed
 * (tax, levy and adjustments together), with how many invoices they came from.
 */
public record TaxSummaryResponse(LocalDate from, LocalDate to, String homeCurrency, long invoices, BigDecimal tax,
        BigDecimal levy, BigDecimal adjustments, BigDecimal total) {
}
