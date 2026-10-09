package net.officefloor.hq.app.fx;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Payload to record what one unit of a currency is worth in the home currency from a date. */
public record FxRateRequest(
        @NotBlank String currency,
        @NotNull LocalDate date,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 6) BigDecimal rate) {
}
