package net.officefloor.hq.app.taxadjustment;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Payload to record a manual tax adjustment: a signed amount in the home currency, dated within its period. */
public record TaxAdjustmentRequest(
        @NotNull LocalDate date,
        @NotNull @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @Size(max = 200) String reason) {
}
