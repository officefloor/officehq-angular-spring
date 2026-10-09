package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;

public record InvoiceResponse(Long id, Long projectId, BigDecimal amount, InvoiceStatus status) {

    static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(invoice.getId(), invoice.getProject().getId(), invoice.getAmount(),
                invoice.getStatus());
    }
}
