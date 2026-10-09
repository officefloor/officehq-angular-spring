package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.util.List;

/**
 * Summary figures for the dashboard, with the clients who owe the most. What is outstanding is
 * given per currency, since amounts in different currencies cannot be added together. What is overdue
 * (left to pay on overdue invoices plus the late fees they have accrued) is one total in the home currency.
 */
public record DashboardResponse(long clients, long projects, List<CurrencyTotal> outstanding, long overdue,
        String homeCurrency, BigDecimal overdueAmount, List<TopClient> topClients) {

    /** What is still owed in one currency. */
    public record CurrencyTotal(String currency, BigDecimal amount) {
    }

    /** A client and what they still owe, in their currency. */
    public record TopClient(Long id, String name, String currency, BigDecimal outstanding) {
    }
}
