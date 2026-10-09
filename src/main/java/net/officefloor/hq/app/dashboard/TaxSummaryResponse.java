package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The tax charged on invoices issued on or between two dates, in the home currency: the sales tax and the levy
 * (second tax) separately, and together as the total, with how many invoices they came from.
 */
public record TaxSummaryResponse(LocalDate from, LocalDate to, String homeCurrency, long invoices, BigDecimal tax,
        BigDecimal levy, BigDecimal total) {
}
