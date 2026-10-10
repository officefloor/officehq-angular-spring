package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * How old the debt across all clients is as at today, in the home currency: what is left to pay on sent invoices
 * split by how many days past due each one is — current (not yet due, or up to 30 days overdue), 31 to 60 days, and
 * more than 60 days — with the total of them, and the invoices that make up each bucket.
 */
public record AgingReportResponse(LocalDate asOf, String homeCurrency, BigDecimal current, BigDecimal days30To60,
        BigDecimal days60Plus, BigDecimal total, List<Line> invoices) {

    /** The age bucket an invoice falls in. */
    public enum Bucket {
        CURRENT, DAYS_30_60, DAYS_60_PLUS
    }

    /** One invoice behind the report: what is left to pay on it in the home currency, and which bucket it is in. */
    public record Line(Long invoiceId, Long projectId, Long clientId, String clientName, LocalDate dueDate,
            long daysOverdue, Bucket bucket, BigDecimal amount) {
    }
}
