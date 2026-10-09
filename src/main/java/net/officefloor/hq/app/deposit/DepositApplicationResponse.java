package net.officefloor.hq.app.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.payment.PaymentResponse;

/** Deposits put toward a client's invoices and the payment recorded against each invoice. */
public record DepositApplicationResponse(Long id, Long clientId, BigDecimal amount, LocalDate date,
        List<PaymentResponse> allocations) {

    public static DepositApplicationResponse from(DepositApplication application, List<PaymentResponse> allocations) {
        return new DepositApplicationResponse(application.getId(), application.getClientId(),
                application.getAmount(), application.getDate(), allocations);
    }
}
