package net.officefloor.hq.app.adjustmentnote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** A correction to a sent invoice: an amount added to (or, when negative, taken off) its total, and why. */
@Entity
@Table(name = "adjustment_note")
public class AdjustmentNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    protected AdjustmentNote() {
    }

    public AdjustmentNote(Long invoiceId, BigDecimal amount, String reason, Instant issuedAt) {
        this.invoiceId = invoiceId;
        this.amount = amount.setScale(2);
        this.reason = reason;
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

    public String getReason() {
        return reason;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
