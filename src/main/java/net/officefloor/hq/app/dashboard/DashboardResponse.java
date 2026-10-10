package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.util.List;

/**
 * Summary figures for the dashboard, with the clients who owe the most. What is outstanding is
 * given per currency, since amounts in different currencies cannot be added together. What is overdue
 * (left to pay on overdue invoices plus the late fees and instalment interest they have built up) is one total in
 * the home currency, and is also split by how many days overdue each invoice is.
 * What is outstanding is also given as one grand total in the home currency, each invoice converted at the
 * exchange rate from its own issue date. Also how many tasks are not yet done and how many of those are past their due date,
 * and the average number of days clients take to pay (from issue date to final payment on paid invoices; null when
 * there are none), and how many new clients were taken on in the current month, and what was billed (invoices issued and sent) in the current month, in the home
 * currency, and the share of everything billed that has been collected as a whole percentage (null when nothing
 * has been billed).
 */
public record DashboardResponse(long clients, long projects, List<CurrencyTotal> outstanding,
        BigDecimal outstandingHome, long overdue,
        String homeCurrency, BigDecimal overdueAmount, OverdueBuckets overdueBuckets, List<TopClient> topClients,
        long openTasks, long overdueTasks, Long averageDaysToPay, long newClientsThisMonth,
        BigDecimal billingsThisMonth, Long collectionRate) {

    /** What is still owed in one currency. */
    public record CurrencyTotal(String currency, BigDecimal amount) {
    }

    /** What is overdue, in the home currency, by how many days past due: up to 30, 31 to 60, and more than 60. */
    public record OverdueBuckets(BigDecimal days0To30, BigDecimal days31To60, BigDecimal days60Plus) {
    }

    /** A client and what they still owe, in their currency and converted into the home currency. */
    public record TopClient(Long id, String name, String currency, BigDecimal outstanding, BigDecimal outstandingHome) {
    }
}
