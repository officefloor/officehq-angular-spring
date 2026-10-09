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

/** One thing an invoice charges for: a description, how many (in what unit), and the price of each. */
@Entity
@Table(name = "invoice_line_item")
public class InvoiceLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal qty;

    @Column(length = 50)
    private String unit;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "tax_exempt", nullable = false)
    private boolean taxExempt;

    protected InvoiceLineItem() {
    }

    InvoiceLineItem(Invoice invoice, String description, BigDecimal qty, String unit, BigDecimal unitPrice,
            boolean taxExempt) {
        this.invoice = invoice;
        this.description = description;
        this.qty = qty;
        this.unit = unit;
        this.unitPrice = unitPrice;
        this.taxExempt = taxExempt;
    }

    void update(String description, BigDecimal qty, String unit, BigDecimal unitPrice, boolean taxExempt) {
        this.description = description;
        this.qty = qty;
        this.unit = unit;
        this.unitPrice = unitPrice;
        this.taxExempt = taxExempt;
    }

    public Long getId() {
        return id;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getQty() {
        return qty;
    }

    /** What the quantity counts, such as hours; null when the line does not say. */
    public String getUnit() {
        return unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /** Whether this line is tax-free: it is not taxable. */
    public boolean isTaxExempt() {
        return taxExempt;
    }

    /** What this line charges: quantity times unit price, to the cent. */
    public BigDecimal getAmount() {
        return qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }
}
