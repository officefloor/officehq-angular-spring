package net.officefloor.hq.app.invoices;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** The part of a payment that goes to one invoice. */
@Embeddable
public class PaymentAllocation {

    @Column(name = "invoice_id")
    private Long invoiceId;

    private BigDecimal amount;

    /** The day this part was applied, when later than the payment (credit used); null for the payment's date. */
    @Column(name = "applied_date")
    private LocalDate date;

    protected PaymentAllocation() {
    }

    /** A part of a payment applied on the given day rather than the day it was paid. */
    public PaymentAllocation(Long invoiceId, BigDecimal amount, LocalDate date) {
        this(invoiceId, amount);
        this.date = date;
    }

    public PaymentAllocation(Long invoiceId, BigDecimal amount) {
        this.invoiceId = invoiceId;
        this.amount = amount;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    /** The day this part was applied, or null when on the payment's own date. */
    public LocalDate getDate() {
        return date;
    }
}
