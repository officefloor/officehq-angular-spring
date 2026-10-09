package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload to add an invoice to a project. The dates are optional: the issue date defaults to today
 * and the due date to {@link #DEFAULT_TERM_DAYS} days after the issue date.
 */
public record InvoiceRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        LocalDate issuedDate,
        LocalDate dueDate) {

    /** Days between issue and due date when no due date is given. */
    public static final int DEFAULT_TERM_DAYS = 30;
}
