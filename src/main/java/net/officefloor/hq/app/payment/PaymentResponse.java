package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A payment against an invoice: {@code amount} is what it settles, in the invoice's currency; {@code paidAmount}
 * and {@code paidCurrency} are what was received when it was paid in another currency (null otherwise).
 */
public record PaymentResponse(Long id, Long invoiceId, BigDecimal amount, LocalDate date, BigDecimal paidAmount,
        String paidCurrency) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getInvoiceId(), payment.getAmount(),
                payment.getDate(), payment.getPaidAmount(), payment.getPaidCurrency());
    }
}
