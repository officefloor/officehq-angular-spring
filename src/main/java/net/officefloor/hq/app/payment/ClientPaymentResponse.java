package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A lump payment from a client and the payment recorded against each invoice it was split across. */
public record ClientPaymentResponse(Long id, Long clientId, BigDecimal amount, LocalDate date,
        List<PaymentResponse> allocations) {

    static ClientPaymentResponse from(ClientPayment lump, List<PaymentResponse> allocations) {
        return new ClientPaymentResponse(lump.getId(), lump.getClientId(), lump.getAmount(), lump.getDate(),
                allocations);
    }
}
