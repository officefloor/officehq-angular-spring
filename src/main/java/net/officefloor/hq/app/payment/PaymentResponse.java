package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentResponse(Long id, Long invoiceId, BigDecimal amount, LocalDate date) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getInvoiceId(), payment.getAmount(),
                payment.getDate());
    }
}
