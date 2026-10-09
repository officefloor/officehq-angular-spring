package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;

/** One discount on an invoice: its percentage or flat amount, and how much it actually takes off. */
public record InvoiceDiscountResponse(Long id, BigDecimal discountPct, BigDecimal discountAmount, BigDecimal amount) {
}
