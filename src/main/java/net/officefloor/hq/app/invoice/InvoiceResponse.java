package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvoiceResponse(Long id, Long projectId, BigDecimal amount, InvoiceStatus status,
        LocalDate issuedDate, LocalDate dueDate) {

    static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(invoice.getId(), invoice.getProject().getId(), invoice.getAmount(),
                invoice.getStatus(), invoice.getIssuedDate(), invoice.getDueDate());
    }
}
