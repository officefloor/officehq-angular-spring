package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The revenue billed on or between two dates, in the home currency: the total of the invoices issued in the range
 * that were sent, with how many invoices it came from.
 */
public record RevenueReportResponse(LocalDate from, LocalDate to, String homeCurrency, long invoices,
        BigDecimal total) {
}
