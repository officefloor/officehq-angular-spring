package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.client.Currency;

/**
 * An invoice, with how much of it is still left to pay after the payments made and credit notes raised against
 * it (nothing once void).
 */
public record InvoiceResponse(Long id, Long projectId, Currency currency, BigDecimal amount, InvoiceStatus status,
        LocalDate issuedDate, LocalDate dueDate, BigDecimal amountDue) {

    static InvoiceResponse from(Invoice invoice, BigDecimal paid, BigDecimal credited) {
        return new InvoiceResponse(invoice.getId(), invoice.getProject().getId(),
                invoice.getProject().getClient().getCurrency(), invoice.getAmount(),
                invoice.getStatus(), invoice.getIssuedDate(), invoice.getDueDate(),
                invoice.amountDue(paid, credited));
    }
}
