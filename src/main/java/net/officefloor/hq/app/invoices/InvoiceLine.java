package net.officefloor.hq.app.invoices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    /**
     * The increment the line is billed in, from its client's rate card for its unit type (0.25 of an
     * hour, packs of 5): it is priced on its quantity rounded up to the next whole increment; null for none.
     */
    @Column(name = "billing_increment")
    private BigDecimal increment;

    /**
     * The minimum units the line is billed, from its client's rate card for its unit type (a call-out is at
     * least 2 hours): it is billed at least that many units, after rounding up to its increment; null for none.
     */
    @Column(name = "billing_min_units")
    private BigDecimal minUnits;

    /**
     * Whether the line is work out of hours, charged at time and a half: its unit price (and, when tiered, each
     * band's price) is 1.5 times the price it was given, rounded half up to the cent.
     */
    private boolean overtime;

    /**
     * Whether the line's price comes from its client's rate card for its unit type: on a draft it follows the
     * card when that rate is changed. Sending fixes the price on the line and clears it.
     */
    @Column(name = "card_priced")
    private boolean cardPriced;

    /** The invoice the line is on, whose earlier lines of the same tiered unit type it carries on from; null while it is on none. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", insertable = false, updatable = false)
    private Invoice invoice;

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
            BigDecimal increment, BigDecimal minUnits, boolean overtime, boolean taxExempt, BigDecimal discountPct,
            BigDecimal taxRate) {
        this(description, qty, unitType, unitPrice, taxExempt, discountPct, taxRate);
        this.tiers = tiers;
        this.increment = increment;
        this.minUnits = minUnits;
        this.overtime = overtime;
    }

    /** A new line with the same description, quantity, unit type, price, tiers, discount, tax rate and exemption. */
    public InvoiceLine copy() {
        return new InvoiceLine(description, qty, unitType, unitPrice, tiers, increment, minUnits, overtime, taxExempt, discountPct, taxRate);
    }

    /** A new credit note line crediting this line, with its description, quantity, price, discount, tax rate and exemption. */
    public InvoiceLine creditCopy() {
        InvoiceLine credit = copy();
        credit.creditOfLine = id;
        return credit;
    }

    /** Changes the line's description, quantity, price, discount, tax rate and exemption; they have already been checked. */
    public void update(String description, BigDecimal qty, String unitType, BigDecimal unitPrice, String tiers,
            BigDecimal increment, BigDecimal minUnits, boolean overtime, boolean taxExempt, BigDecimal discountPct,
            BigDecimal taxRate) {
        this.increment = increment;
        this.minUnits = minUnits;
        this.overtime = overtime;
        this.description = description;
        this.unitType = unitType;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.tiers = tiers;
        this.taxExempt = taxExempt;
        this.discountPct = discountPct;
        this.taxRate = taxRate;
    }

    /** Whether the line's price comes from its client's rate card. */
    public boolean isCardPriced() {
        return cardPriced;
    }

    void setCardPriced(boolean cardPriced) {
        this.cardPriced = cardPriced;
    }

    /** Prices the line afresh from its client's rate card: the rate's single price or tiers, increment and minimum units. */
    void reprice(BigDecimal unitPrice, String tiers, BigDecimal increment, BigDecimal minUnits) {
        this.unitPrice = unitPrice;
        this.tiers = tiers;
        this.increment = increment;
        this.minUnits = minUnits;
    }

    /** Puts the line on the given invoice. */
    void placeOn(Invoice invoice) {
        this.invoice = invoice;
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

    /** The price the line was given (from the rate card, the price list or typed in), before any overtime. */
    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /** Whether the line is charged at time and a half. */
    public boolean isOvertime() {
        return overtime;
    }

    /** The unit price the line is charged: its price, or 1.5 times it rounded half up to the cent when overtime. */
    public BigDecimal chargedUnitPrice() {
        return charged(unitPrice);
    }

    /** The given price as charged on this line: 1.5 times it, rounded half up to the cent, when overtime. */
    private BigDecimal charged(BigDecimal price) {
        return overtime ? price.multiply(OVERTIME_RATE).setScale(2, RoundingMode.HALF_UP) : price;
    }

    /** Overtime is charged at time and a half. */
    private static final BigDecimal OVERTIME_RATE = new BigDecimal("1.5");

    /** The increment the line is billed in; null for none. */
    public BigDecimal getIncrement() {
        return increment;
    }

    /** The minimum units the line is billed; null for none. */
    public BigDecimal getMinUnits() {
        return minUnits;
    }

    /**
     * The quantity the line is billed on: its quantity rounded up to the next whole increment (as entered without
     * one), and then at least its minimum units.
     */
    public BigDecimal billedQty() {
        BigDecimal billed = increment == null || increment.signum() <= 0 ? qty
                : qty.divide(increment, 0, RoundingMode.CEILING).multiply(increment);
        return minUnits != null && billed.compareTo(minUnits) < 0 ? minUnits : billed;
    }

    /** Whether the line is priced in progressive tiers rather than at its unit price. */
    public boolean isTiered() {
        return tiers != null;
    }

    /** The stored tiers the line is priced in (see {@link RateTiers}); null when it has a single unit price. */
    String tiers() {
        return tiers;
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
     * Billed quantity times the charged unit price (time and a half when overtime) — or, for a tiered line, the charge for its billed units at the bands they
     * fall in, carrying on from where the invoice's earlier lines of its unit type stopped — rounded half up to the given number of decimals (2, the cent; 0 for whole yen), before
     * the line's discount.
     */
    public BigDecimal grossAmount(int scale) {
        if (tiers != null) {
            BigDecimal before = invoice == null ? BigDecimal.ZERO : invoice.tieredUnitsBefore(this);
            RateTiers bands = RateTiers.parse(tiers);
            if (overtime) {
                bands = new RateTiers(bands.bands().stream()
                        .map(b -> new RateTiers.Band(b.units(), charged(b.unitPrice()))).toList());
            }
            return bands.charge(before, billedQty(), scale);
        }
        return billedQty().multiply(chargedUnitPrice()).setScale(scale, RoundingMode.HALF_UP);
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
