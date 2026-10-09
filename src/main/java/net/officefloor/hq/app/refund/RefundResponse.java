package net.officefloor.hq.app.refund;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RefundResponse(Long id, Long clientId, BigDecimal amount, BigDecimal fromDeposits,
        BigDecimal fromCreditNotes, LocalDate date, String note) {

    static RefundResponse from(Refund refund) {
        return new RefundResponse(refund.getId(), refund.getClientId(), refund.getAmount(),
                refund.getFromDeposits(), refund.getFromCreditNotes(), refund.getDate(), refund.getNote());
    }
}
