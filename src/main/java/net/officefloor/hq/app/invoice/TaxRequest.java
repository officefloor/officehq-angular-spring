package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Payload to set the sales tax percentage added to a draft invoice; zero removes the tax. */
public record TaxRequest(
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal taxPct) {
}
