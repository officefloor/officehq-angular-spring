package net.officefloor.hq.app.settings;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/** Payload to set what the business aims to bill over the year, in the home currency; null clears the target. */
public record BillingTargetRequest(
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 2) BigDecimal billingTarget) {
}
