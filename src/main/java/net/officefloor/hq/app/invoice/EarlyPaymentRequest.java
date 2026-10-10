package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Payload to set the early-payment discount offered on a draft invoice: the percentage taken off if it is
 * paid within the client's early-payment window (part of their payment terms); zero removes the offer.
 */
public record EarlyPaymentRequest(
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal earlyPaymentPct) {
}
