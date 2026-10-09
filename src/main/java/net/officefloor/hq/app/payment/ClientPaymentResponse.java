package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A lump payment from a client and the payment recorded against each invoice it was split across, with how much of
 * the client's credit it used up ({@code fromDeposits}, {@code fromCreditNotes}) and how much of the money received
 * was kept as credit for them ({@code toCredit}).
 */
public record ClientPaymentResponse(Long id, Long clientId, BigDecimal amount, LocalDate date,
        BigDecimal fromDeposits, BigDecimal fromCreditNotes, BigDecimal toCredit, List<PaymentResponse> allocations) {

    static ClientPaymentResponse from(ClientPayment lump, List<PaymentResponse> allocations) {
        return new ClientPaymentResponse(lump.getId(), lump.getClientId(), lump.getAmount(), lump.getDate(),
                lump.getFromDeposits(), lump.getFromCreditNotes(), lump.getToCredit(), allocations);
    }
}
