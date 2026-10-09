package net.officefloor.hq.app.currency;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Payload to change the step a currency's amounts are rounded to, in whole cents (0.01 or more). */
public record CurrencyRoundingRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal roundingStep) {
}
