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

/** One thing an invoice charges for: a description, how many, and the price of each. */
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

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    protected InvoiceLineItem() {
    }

    InvoiceLineItem(Invoice invoice, String description, BigDecimal qty, BigDecimal unitPrice) {
        this.invoice = invoice;
        this.description = description;
        this.qty = qty;
        this.unitPrice = unitPrice;
    }

    void update(String description, BigDecimal qty, BigDecimal unitPrice) {
        this.description = description;
        this.qty = qty;
        this.unitPrice = unitPrice;
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

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /** What this line charges: quantity times unit price, to the cent. */
    public BigDecimal getAmount() {
        return qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }
}
