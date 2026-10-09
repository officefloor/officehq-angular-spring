package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.client.Currency;

/**
 * Summary figures for the dashboard, with the clients who owe the most. What is outstanding is
 * given per currency, since amounts in different currencies cannot be added together.
 */
public record DashboardResponse(long clients, long projects, List<CurrencyTotal> outstanding, long overdue,
        List<TopClient> topClients) {

    /** What is still owed in one currency. */
    public record CurrencyTotal(Currency currency, BigDecimal amount) {
    }

    /** A client and what they still owe, in their currency. */
    public record TopClient(Long id, String name, Currency currency, BigDecimal outstanding) {
    }
}
