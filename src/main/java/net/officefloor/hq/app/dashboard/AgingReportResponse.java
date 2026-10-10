package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How old the debt across all clients is as at today, in the home currency: what is left to pay on sent invoices
 * split by how many days past due each one is — current (not yet due, or up to 30 days overdue), 31 to 60 days, and
 * more than 60 days — with the total of them.
 */
public record AgingReportResponse(LocalDate asOf, String homeCurrency, BigDecimal current, BigDecimal days30To60,
        BigDecimal days60Plus, BigDecimal total) {
}
