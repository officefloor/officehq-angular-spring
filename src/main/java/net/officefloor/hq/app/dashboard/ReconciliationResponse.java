package net.officefloor.hq.app.dashboard;

import java.math.BigDecimal;
import java.util.List;

/**
 * Proves the books balance: every client's balance in the currency the dashboard's totals are shown in, the total
 * of those balances, and the dashboard's outstanding figure it is checked against, with any difference between them.
 */
public record ReconciliationResponse(String currency, List<ClientBalance> clients, BigDecimal total,
        BigDecimal outstanding, BigDecimal difference, boolean balanced) {

    /** What one client still owes, counted by the same rule as the dashboard's outstanding figure. */
    public record ClientBalance(Long id, String name, BigDecimal balance) {
    }
}
