package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Payload to set the settlement rebate offered on a draft invoice: the percentage of the amount given back
 * when it is paid before the due date; zero removes the offer.
 */
public record RebateRequest(
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal rebatePct) {
}
