package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An invoice, with how much of it is still left to pay after the payments made and credit notes raised against
 * it (nothing once void), and whether any credit has been put against it.
 */
public record InvoiceResponse(Long id, Long projectId, String currency, BigDecimal amount, InvoiceStatus status,
        LocalDate issuedDate, LocalDate dueDate, BigDecimal amountDue,
        boolean creditApplied) {

    static InvoiceResponse from(Invoice invoice, BigDecimal paid, BigDecimal credited) {
        return new InvoiceResponse(invoice.getId(), invoice.getProject().getId(),
                invoice.getProject().getClient().getCurrency(), invoice.getAmount(),
                invoice.statusFor(paid, credited), invoice.getIssuedDate(), invoice.getDueDate(),
                invoice.amountDue(paid, credited), credited.signum() > 0);
    }
}
