package net.officefloor.hq.app.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload to record a payment against an invoice. The amount is in {@code currency}; without one it is in the
 * invoice's currency.
 */
public record PaymentRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull LocalDate date,
        @Pattern(regexp = "[A-Za-z]{3}") String currency) {
}
