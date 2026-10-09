package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;

public record LineItemResponse(Long id, String description, BigDecimal qty, String unit, BigDecimal unitPrice,
        BigDecimal amount, boolean taxExempt, BigDecimal discountPct, BigDecimal grossAmount) {

    static LineItemResponse from(InvoiceLineItem item) {
        return new LineItemResponse(item.getId(), item.getDescription(), item.getQty(), item.getUnit(), item.getUnitPrice(),
                item.getAmount(), item.isTaxExempt(), item.getDiscountPct(), item.getGrossAmount());
    }
}
