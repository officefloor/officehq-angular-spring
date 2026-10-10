package net.officefloor.hq.app.recurring;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload to set up an invoice that repeats for a fixed amount, starting on the given day. When
 * {@code prorateFirst} is set the first invoice bills only the days left in its {@code periodDays}-day period
 * (30 when not given).
 */
public record RecurringInvoiceRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull RecurringFrequency frequency,
        @NotNull LocalDate nextDate,
        @Min(1) @Max(366) Integer periodDays,
        Boolean prorateFirst) {
}
