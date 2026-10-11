package net.officefloor.hq.app.invoices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import net.officefloor.hq.app.clients.RateTiers;

/**
 * One line of an invoice: a quantity (up to two decimals) times a unit price, less the line's own
 * percentage discount.
 */
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

    /** The line's own percentage discount, 0 to 100 with up to two decimals; 0 is none. */
    @Column(name = "discount_pct")
    private BigDecimal discountPct = BigDecimal.ZERO;

    /** The line's own tax percentage, 0 to 100 with up to two decimals; null uses the workspace rate. */
    @Column(name = "tax_rate")
    private BigDecimal taxRate;

    /** The id of the invoice line this credit note line credits; null when it credits no line in particular. */
    @Column(name = "credit_of_line")
    private Long creditOfLine;

    /** The unit type the line is billed in (hour, day, ...); null for none. */
    @Column(name = "unit_type")
    private String unitType;

    /**
     * The progressive tiers the line is priced in, from its client's rate card (see {@link RateTiers});
     * null when it has a single unit price. A tiered line keeps its first band's price as its unit price.
     */
    private String tiers;

    protected InvoiceLine() {
    }

    public InvoiceLine(String description, BigDecimal qty, BigDecimal unitPrice, boolean taxExempt,
            BigDecimal discountPct, BigDecimal taxRate) {
        this.description = description;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.taxExempt = taxExempt;
        this.discountPct = discountPct;
        this.taxRate = taxRate;
    }

    public InvoiceLine(String description, BigDecimal qty, String unitType, BigDecimal unitPrice, boolean taxExempt,
            BigDecimal discountPct, BigDecimal taxRate) {
        this(description, qty, unitPrice, taxExempt, discountPct, taxRate);
        this.unitType = unitType;
    }

    public InvoiceLine(String description, BigDecimal qty, String unitType, BigDecimal unitPrice, String tiers,
            boolean taxExempt, BigDecimal discountPct, BigDecimal taxRate) {
        this(description, qty, unitType, unitPrice, taxExempt, discountPct, taxRate);
        this.tiers = tiers;
    }

    /** A new line with the same description, quantity, unit type, price, tiers, discount, tax rate and exemption. */
    public InvoiceLine copy() {
        return new InvoiceLine(description, qty, unitType, unitPrice, tiers, taxExempt, discountPct, taxRate);
    }

    /** A new credit note line crediting this line, with its description, quantity, price, discount, tax rate and exemption. */
    public InvoiceLine creditCopy() {
        InvoiceLine credit = copy();
        credit.creditOfLine = id;
        return credit;
    }

    /** Changes the line's description, quantity, price, discount, tax rate and exemption; they have already been checked. */
    public void update(String description, BigDecimal qty, String unitType, BigDecimal unitPrice, String tiers,
            boolean taxExempt, BigDecimal discountPct, BigDecimal taxRate) {
        this.description = description;
        this.unitType = unitType;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.tiers = tiers;
        this.taxExempt = taxExempt;
        this.discountPct = discountPct;
        this.taxRate = taxRate;
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

    public String getUnitType() {
        return unitType;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /** Whether the line is priced in progressive tiers rather than at its unit price. */
    public boolean isTiered() {
        return tiers != null;
    }

    public boolean isTaxExempt() {
        return taxExempt;
    }

    public BigDecimal getDiscountPct() {
        return discountPct;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public Long getCreditOfLine() {
        return creditOfLine;
    }

    /** The rate the line is taxed at: its own rate, or the given workspace rate when it has none. */
    public BigDecimal taxRate(BigDecimal workspaceRate) {
        return taxRate == null ? workspaceRate : taxRate;
    }

    /**
     * Quantity times unit price — or, for a tiered line, the total of its bands starting from the
     * first — rounded half up to the given number of decimals (2, the cent; 0 for whole yen), before
     * the line's discount.
     */
    public BigDecimal grossAmount(int scale) {
        if (tiers != null) {
            return RateTiers.parse(tiers).total(qty, scale);
        }
        return qty.multiply(unitPrice).setScale(scale, RoundingMode.HALF_UP);
    }

    /** The line's discount: its percentage of the gross amount, rounded down to the given number of decimals. */
    public BigDecimal discount(int scale) {
        return grossAmount(scale).multiply(discountPct).divide(BigDecimal.valueOf(100))
                .setScale(scale, RoundingMode.DOWN);
    }

    /** The line amount, to the given number of decimals: the gross amount less the line's discount. */
    public BigDecimal amount(int scale) {
        return grossAmount(scale).subtract(discount(scale));
    }
}
