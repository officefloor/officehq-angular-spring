package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload to add an invoice to a project. Everything is optional: without an amount the invoice
 * starts as an empty draft to be built up from line items, while an amount is carried as a single
 * line. The issue date defaults to today and the due date to the client's payment terms (or
 * {@link #DEFAULT_TERM_DAYS} days when none are agreed) after the issue date. The sales tax percentage defaults to the default tax rate in the settings.
 */
public record InvoiceRequest(
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        LocalDate issuedDate,
        LocalDate dueDate,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal taxPct) {

    /** Days between issue and due date when no due date is given and the client has no payment terms. */
    public static final int DEFAULT_TERM_DAYS = 30;

    /** Days between issue and due date for a client with the given payment terms, which may be none. */
    public static int termDays(Integer paymentTermsDays) {
        return paymentTermsDays != null ? paymentTermsDays : DEFAULT_TERM_DAYS;
    }

    /** Description of the single line an invoice raised as one figure is given. */
    public static final String SINGLE_AMOUNT_DESCRIPTION = "Invoice amount";
}
