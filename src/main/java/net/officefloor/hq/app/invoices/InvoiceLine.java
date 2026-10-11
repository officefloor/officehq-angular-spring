package net.officefloor.hq.app.invoices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** One line of an invoice: a quantity (up to two decimals) times a unit price. */
@Entity
@Table(name = "invoice_line")
public class InvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String description;

    private BigDecimal qty;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    /** Whether the line is left out of the taxed amount, e.g. postage. */
    @Column(name = "tax_exempt")
    private boolean taxExempt;

    protected InvoiceLine() {
    }

    public InvoiceLine(String description, BigDecimal qty, BigDecimal unitPrice, boolean taxExempt) {
        this.description = description;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.taxExempt = taxExempt;
    }

    public Long getId() {
        return id;
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

    public boolean isTaxExempt() {
        return taxExempt;
    }

    /** Quantity times unit price, rounded half up to the cent. */
    public BigDecimal amount() {
        return qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }
}
