package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The revenue billed on or between two dates (or over all time when no dates are given), in the home currency: the
 * total of the invoices issued in the range that were sent, with how many invoices it came from, broken down by job
 * (project) with the highest-earning job first.
 */
public record RevenueReportResponse(LocalDate from, LocalDate to, String homeCurrency, long invoices,
        BigDecimal total, List<JobRevenue> jobs) {

    /** The revenue billed for one job (project), in the home currency. */
    public record JobRevenue(Long projectId, String projectName, String clientName, long invoices,
            BigDecimal amount) {
    }
}
