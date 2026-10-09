package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An invoice, with how much of it is still left to pay after the payments made against it (nothing once void). */
public record InvoiceResponse(Long id, Long projectId, BigDecimal amount, InvoiceStatus status,
        LocalDate issuedDate, LocalDate dueDate, BigDecimal amountDue) {

    static InvoiceResponse from(Invoice invoice, BigDecimal paid) {
        return new InvoiceResponse(invoice.getId(), invoice.getProject().getId(), invoice.getAmount(),
                invoice.getStatus(), invoice.getIssuedDate(), invoice.getDueDate(),
                invoice.amountDue(paid));
    }
}
