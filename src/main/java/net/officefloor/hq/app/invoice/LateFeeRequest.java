package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Payload to set the late fee charged for each day a sent invoice is overdue; zero charges none. */
public record LateFeeRequest(@NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal lateFeePerDay) {
}
