package net.officefloor.hq.app.deposit;

import java.math.BigDecimal;
import java.util.List;

/** The deposits held for a client, oldest first, and their total. */
public record ClientDepositsResponse(BigDecimal total, List<DepositResponse> deposits) {
}
