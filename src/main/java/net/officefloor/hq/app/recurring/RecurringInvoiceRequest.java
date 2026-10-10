package net.officefloor.hq.app.recurring;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Payload to set up an invoice that repeats for a fixed amount, starting on the given day. */
public record RecurringInvoiceRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull RecurringFrequency frequency,
        @NotNull LocalDate nextDate) {
}
