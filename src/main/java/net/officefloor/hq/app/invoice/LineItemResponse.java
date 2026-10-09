package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;

public record LineItemResponse(Long id, String description, BigDecimal qty, BigDecimal unitPrice,
        BigDecimal amount) {

    static LineItemResponse from(InvoiceLineItem item) {
        return new LineItemResponse(item.getId(), item.getDescription(), item.getQty(), item.getUnitPrice(),
                item.getAmount());
    }
}
