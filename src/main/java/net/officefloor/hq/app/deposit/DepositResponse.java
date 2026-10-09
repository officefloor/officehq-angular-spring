package net.officefloor.hq.app.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DepositResponse(Long id, Long clientId, BigDecimal amount, LocalDate date) {

    static DepositResponse from(Deposit deposit) {
        return new DepositResponse(deposit.getId(), deposit.getClientId(), deposit.getAmount(), deposit.getDate());
    }
}
