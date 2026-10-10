package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An invoice, with how much of it the client owes right now after the payments made and credit notes raised
 * against it (nothing once void), the amount held back as retention and not due yet (shown separately), and
 * whether any credit has been put against it. An owing invoice on an instalment plan also carries how it is keeping
 * to its schedule (null otherwise).
 */
public record InvoiceResponse(Long id, Long projectId, String currency, BigDecimal amount, InvoiceStatus status,
        LocalDate issuedDate, LocalDate dueDate, BigDecimal amountDue,
        BigDecimal retention, boolean creditApplied, ScheduleStatus schedule) {

    static InvoiceResponse from(Invoice invoice, BigDecimal paid, BigDecimal credited) {
        return from(invoice, paid, credited, null);
    }

    static InvoiceResponse from(Invoice invoice, BigDecimal paid, BigDecimal credited, ScheduleStatus schedule) {
        return new InvoiceResponse(invoice.getId(), invoice.getProject().getId(),
                invoice.getCurrency(), invoice.getAmount(),
                invoice.statusFor(paid, credited), invoice.getIssuedDate(), invoice.getDueDate(),
                invoice.dueNow(paid, credited), invoice.getRetention(), credited.signum() > 0, schedule);
    }
}
