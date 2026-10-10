package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A lump payment from a client, in the client's currency, without the invoices it was split across. */
public record ClientPaymentSummaryResponse(Long id, Long clientId, BigDecimal amount, LocalDate date) {

    static ClientPaymentSummaryResponse from(ClientPayment lump) {
        return new ClientPaymentSummaryResponse(lump.getId(), lump.getClientId(), lump.getAmount(), lump.getDate());
    }
}
