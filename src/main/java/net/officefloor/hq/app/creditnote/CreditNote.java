package net.officefloor.hq.app.creditnote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** An amount given back to a client against an invoice. */
@Entity
@Table(name = "credit_note")
public class CreditNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    protected CreditNote() {
    }

    public CreditNote(Long invoiceId, BigDecimal amount, Instant issuedAt) {
        this.invoiceId = invoiceId;
        this.amount = amount.setScale(2);
        this.issuedAt = issuedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
