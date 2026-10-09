package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Payload to set the flat surcharge (such as a handling fee) added to a draft invoice; zero removes it. */
public record SurchargeRequest(
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal surcharge) {
}
