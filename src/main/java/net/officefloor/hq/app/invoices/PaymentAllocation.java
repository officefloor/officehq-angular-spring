package net.officefloor.hq.app.invoices;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** The part of a payment that goes to one invoice. */
@Embeddable
public class PaymentAllocation {

    @Column(name = "invoice_id")
    private Long invoiceId;

    private BigDecimal amount;

    protected PaymentAllocation() {
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
}
