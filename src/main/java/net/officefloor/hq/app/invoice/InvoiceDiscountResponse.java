package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;

/** One discount on an invoice: its percentage (and any cap on it) or flat amount, and how much it actually takes off. */
public record InvoiceDiscountResponse(Long id, BigDecimal discountPct, BigDecimal discountAmount, BigDecimal discountCap,
        BigDecimal amount) {
}
