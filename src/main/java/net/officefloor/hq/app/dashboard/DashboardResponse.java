package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.util.List;

/**
 * Summary figures for the dashboard, with the clients who owe the most. What is outstanding is
 * given per currency, since amounts in different currencies cannot be added together. What is overdue
 * (left to pay on overdue invoices plus the late fees and instalment interest they have built up) is one total in
 * the home currency, and is also split by how many days overdue each invoice is.
 * What is outstanding is also given as one grand total in the home currency, each invoice converted at the
 * exchange rate from its own issue date.
 */
public record DashboardResponse(long clients, long projects, List<CurrencyTotal> outstanding,
        BigDecimal outstandingHome, long overdue,
        String homeCurrency, BigDecimal overdueAmount, OverdueBuckets overdueBuckets, List<TopClient> topClients) {

    /** What is still owed in one currency. */
    public record CurrencyTotal(String currency, BigDecimal amount) {
    }

    /** What is overdue, in the home currency, by how many days past due: up to 30, 31 to 60, and more than 60. */
    public record OverdueBuckets(BigDecimal days0To30, BigDecimal days31To60, BigDecimal days60Plus) {
    }

    /** A client and what they still owe, in their currency. */
    public record TopClient(Long id, String name, String currency, BigDecimal outstanding) {
    }
}
