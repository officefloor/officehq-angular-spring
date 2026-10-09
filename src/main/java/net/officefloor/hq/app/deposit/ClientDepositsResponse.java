package net.officefloor.hq.app.deposit;

import java.math.BigDecimal;
import java.util.List;

/**
 * The deposits a client has paid, oldest first; {@code total} is what is still held once any
 * already put toward invoices ({@code applied}) is taken off.
 */
public record ClientDepositsResponse(BigDecimal total, BigDecimal applied, List<DepositResponse> deposits) {
}
