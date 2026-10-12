package net.officefloor.hq.app.invoices;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** An invoice whose purchase order number one part of a payment's remittance matched, and how closely. */
@Embeddable
public class PaymentMatch {

    @Column(name = "invoice_id")
    private Long invoiceId;

    /** The Jaro-Winkler similarity, to three decimals. */
    private BigDecimal score;

    protected PaymentMatch() {
    }

    public PaymentMatch(Long invoiceId, BigDecimal score) {
        this.invoiceId = invoiceId;
        this.score = score;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public BigDecimal getScore() {
        return score;
    }
}
