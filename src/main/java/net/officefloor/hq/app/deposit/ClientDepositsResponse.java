package net.officefloor.hq.app.deposit;

import java.math.BigDecimal;
import java.util.List;

/**
 * The deposits a client has paid, oldest first; {@code total} is what is still held once any
 * already put toward invoices ({@code applied}) or refunded to the client ({@code refunded}) is taken off.
 */
public record ClientDepositsResponse(BigDecimal total, BigDecimal applied, BigDecimal refunded, List<DepositResponse> deposits) {
}
