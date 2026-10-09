package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload to add an invoice to a project. Everything is optional: without an amount the invoice
 * starts as an empty draft to be built up from line items, while an amount is carried as a single
 * line. The issue date defaults to today and the due date to {@link #DEFAULT_TERM_DAYS} days after
 * the issue date.
 */
public record InvoiceRequest(
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        LocalDate issuedDate,
        LocalDate dueDate) {

    /** Days between issue and due date when no due date is given. */
    public static final int DEFAULT_TERM_DAYS = 30;

    /** Description of the single line an invoice raised as one figure is given. */
    public static final String SINGLE_AMOUNT_DESCRIPTION = "Invoice amount";
}
