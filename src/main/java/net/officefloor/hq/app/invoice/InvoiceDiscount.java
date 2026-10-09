package net.officefloor.hq.app.invoice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** One discount on an invoice: either a percentage or a flat amount taken off its subtotal, never both. */
@Entity
@Table(name = "invoice_discount")
public class InvoiceDiscount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "discount_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPct = BigDecimal.ZERO.setScale(2);

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO.setScale(2);

    protected InvoiceDiscount() {
    }

    InvoiceDiscount(Invoice invoice, BigDecimal discountPct, BigDecimal discountAmount) {
        if (discountPct.signum() != 0 && discountAmount.signum() != 0) {
            throw new IllegalArgumentException("A discount is either a percentage or a flat amount, not both");
        }
        this.invoice = invoice;
        this.discountPct = discountPct.setScale(2, RoundingMode.HALF_UP);
        this.discountAmount = discountAmount.setScale(2, RoundingMode.HALF_UP);
    }

    public Long getId() {
        return id;
    }

    /** The percentage taken off the subtotal; zero when this is a flat discount. */
    public BigDecimal getDiscountPct() {
        return discountPct;
    }

    /** The flat amount asked to be taken off the subtotal; zero when this is a percentage discount. */
    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    /** Whether this discount is a percentage of the subtotal rather than a flat amount. */
    public boolean isPercentage() {
        return discountPct.signum() > 0;
    }
}
