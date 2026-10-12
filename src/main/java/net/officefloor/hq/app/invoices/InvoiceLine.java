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
import org.hibernate.annotations.Formula;
import java.math.BigDecimal;
import java.util.Objects;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

    /**
     * For a credit note line crediting a tiered line: how far through the bands the units still to give back reach — the units of
     * the credited line's unit type its invoice took through the bands up to the end of that line, less those of it already given
     * back by issued credit notes before this one. Credits are given back from the top down, so the line is charged for the units
     * just below this. Null for any other line.
     */
    @Formula("((SELECT SUM(GREATEST(CASE WHEN e.billing_increment > 0 THEN CEILING(e.qty / e.billing_increment) * e.billing_increment ELSE e.qty END, COALESCE(e.billing_min_units, 0))) FROM invoice_line o, invoice_line e"
            + " WHERE o.id = credit_of_line AND o.tiers IS NOT NULL AND e.invoice_id = o.invoice_id AND e.tiers IS NOT NULL"
            + " AND e.unit_type = o.unit_type AND e.id <= o.id)"
            + " - COALESCE((SELECT SUM(GREATEST(CASE WHEN c.billing_increment > 0 THEN CEILING(c.qty / c.billing_increment) * c.billing_increment ELSE c.qty END, COALESCE(c.billing_min_units, 0))) FROM invoice_line c, invoice cn"
            + " WHERE c.credit_of_line = credit_of_line AND cn.id = c.invoice_id AND cn.status = 'CREDIT_NOTE' AND cn.id < invoice_id), 0))")
    private BigDecimal creditedUnitsTop;

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
     * Whether the client disputes the line: from its invoice's dispute date until the dispute is resolved, the
     * line (after its share of the invoice discount, with its tax) earns no interest and is not overdue.
     */
    private boolean disputed;

    /**
     * Whether the line's price comes from the rates for its unit type (its client's rate card, or else the price
     * list): on a draft it follows the card when that rate is changed, and sending reprices it at the rates in force
     * on the day it is sent, then fixes the price on the line and clears it.
     */
    @Column(name = "card_priced")
    private boolean cardPriced;

    /**
     * Whether the line was priced from the rates for its unit type (its client's rate card, or else the price list), at a
     * price already negotiated: it takes no share of the invoice discount. Sending keeps it.
     */
    @Column(name = "rate_priced")
    private boolean ratePriced;

    /**
     * The first day of the service period the line is billed for; null for none. With a period the line's quantity times unit price is
     * the price of a whole month, charged by the day (12 / 365 of it a day) for the days of the period (both ends included).
     */
    @Column(name = "period_start")
    private LocalDate periodStart;

    /** The last day of the line's service period, never before its first; null for none. */
    @Column(name = "period_end")
    private LocalDate periodEnd;

    /**
     * The percentage of the line's work done, more than 0 and at most 100 with up to two decimals, for a line billed by progress:
     * its gross amount is that percentage of its full amount, rounded half up to the cent; null bills the whole line.
     */
    @Column(name = "percent_complete")
    private BigDecimal percentComplete;

    /**
     * For a line copied into a progress claim, the percentage of its work already billed (its percent complete on the invoice it
     * was claimed from): the line bills its percent complete of its full amount less this percentage of it, each rounded half up
     * to the cent; its percent complete is never below it. Null when nothing was billed before.
     */
    @Column(name = "billed_pct")
    private BigDecimal billedPct;

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
        InvoiceLine copy = new InvoiceLine(description, qty, unitType, unitPrice, tiers, increment, minUnits, overtime, taxExempt, discountPct, taxRate);
        copy.ratePriced = ratePriced;
        copy.periodStart = periodStart;
        copy.periodEnd = periodEnd;
        copy.percentComplete = percentComplete;
        copy.billedPct = billedPct;
        return copy;
    }

    /** A copy of this line for the next progress claim: its percent complete so far is what it has already billed. */
    public InvoiceLine claimCopy() {
        InvoiceLine claim = copy();
        claim.billedPct = percentComplete;
        return claim;
    }

    /** A new credit note line crediting this line, with its description, quantity, price, discount, tax rate and exemption. */
    public InvoiceLine creditCopy() {
        InvoiceLine credit = copy();
        credit.creditOfLine = id;
        return credit;
    }

    /** Sets the line's quantity (of a copy for an adjustment note), answering the line. */
    InvoiceLine withQty(BigDecimal qty) {
        this.qty = qty;
        return this;
    }

    /** Whether the other line bills the same thing as this one at the same price, discount and tax, whatever its quantity. */
    boolean sameButQty(InvoiceLine other) {
        return Objects.equals(description, other.description) && Objects.equals(unitType, other.unitType)
                && same(unitPrice, other.unitPrice) && Objects.equals(tiers, other.tiers)
                && same(increment, other.increment) && same(minUnits, other.minUnits)
                && overtime == other.overtime && taxExempt == other.taxExempt
                && same(discountPct, other.discountPct) && same(taxRate, other.taxRate)
                && Objects.equals(periodStart, other.periodStart) && Objects.equals(periodEnd, other.periodEnd)
                && same(percentComplete, other.percentComplete);
    }

    /** Whether two amounts are the same: both none, or equal in value. */
    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
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

    /** Sets the line's service period (both null for none); it has already been checked. */
    void setPeriod(LocalDate periodStart, LocalDate periodEnd) {
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    /** The percentage of the line's work done; null when the whole line is billed. */
    public BigDecimal getPercentComplete() {
        return percentComplete;
    }

    /** Sets the percentage of the line's work done (null for the whole line); it has already been checked. */
    void setPercentComplete(BigDecimal percentComplete) {
        this.percentComplete = percentComplete;
    }

    /** Whether the line is billed by progress. */
    public boolean isProgress() {
        return percentComplete != null;
    }

    /** The percentage of the line's work already billed by the claims before; null when none was. */
    public BigDecimal getBilledPct() {
        return billedPct;
    }

    /** Whether the line is billed for a service period. */
    public boolean hasPeriod() {
        return periodStart != null && periodEnd != null;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    /**
     * The share of a whole month the line's service period comes to, charged by the day the same every month: the days of the
     * period (both ends included) times a day rate of 12 / 365 of a month, kept exact; null when the line has no period.
     */
    private BigDecimal periodMonths() {
        if (periodStart == null || periodEnd == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
        return BigDecimal.valueOf(days * 12).divide(BigDecimal.valueOf(365), MathContext.DECIMAL128);
    }

    /** Whether the line's price comes from its client's rate card. */
    public boolean isCardPriced() {
        return cardPriced;
    }

    void setCardPriced(boolean cardPriced) {
        this.cardPriced = cardPriced;
        this.ratePriced = cardPriced;
    }

    /** Fixes the price on the line, so it no longer follows the rates; it stays priced from them. */
    void fixPrice() {
        this.cardPriced = false;
    }

    /** Whether the line was priced from the rates (rate card or price list), and so takes no invoice discount. */
    public boolean isRatePriced() {
        return ratePriced;
    }

    /** Prices the line afresh from its client's rate card: the rate's single price or tiers, increment and minimum units. */
    void reprice(BigDecimal unitPrice, String tiers, BigDecimal increment, BigDecimal minUnits) {
        this.unitPrice = unitPrice;
        this.tiers = tiers;
        this.increment = increment;
        this.minUnits = minUnits;
    }

    /**
     * Raises a typed unit price by the percentage, rounded half up to the cent; a price taken from the rates (rate card or
     * price list) keeps that price.
     */
    void raisePrice(BigDecimal pct) {
        if (ratePriced || cardPriced || unitPrice == null) {
            return;
        }
        unitPrice = unitPrice.multiply(BigDecimal.valueOf(100).add(pct)).divide(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
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

    /** Whether the client disputes the line. */
    public boolean isDisputed() {
        return disputed;
    }

    /** Puts the line in dispute, or takes it out. */
    public void setDisputed(boolean disputed) {
        this.disputed = disputed;
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

    /**
     * The line as it counts towards its invoice's fingerprint: description,quantity,unit price in cents — the
     * description in lower case with runs of spaces made one and trimmed, the quantity without trailing zeros
     * and the unit price it is charged at (0 when priced in tiers).
     */
    public String fingerprint() {
        return fingerprintWith(qty);
    }

    /** The line's {@link #fingerprint} as if it billed the quantity given. */
    String fingerprintWith(BigDecimal quantity) {
        String text = description == null ? "" : description.toLowerCase(java.util.Locale.ROOT).replaceAll(" +", " ").trim();
        BigDecimal cents = isTiered() || unitPrice == null ? BigDecimal.ZERO : chargedUnitPrice().movePointRight(2);
        return text + "," + plain(quantity) + "," + plain(cents);
    }

    /** The line's {@link #fingerprint} without its quantity: lines with the same one bill the same thing at the same price. */
    String fingerprintKey() {
        return fingerprintWith(BigDecimal.ZERO);
    }

    private static String plain(BigDecimal value) {
        return value == null || value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
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
        BigDecimal full = fullAmount(scale);
        // A line billed by progress is charged its percentage of the full amount, rounded half up once more,
        // less what was already billed of it, rounded the same way on its own.
        if (percentComplete == null) {
            return full;
        }
        BigDecimal toDate = share(full, percentComplete, scale);
        return billedPct == null ? toDate : toDate.subtract(share(full, billedPct, scale));
    }

    /** The given percentage of an amount, rounded half up to the given number of decimals. */
    private static BigDecimal share(BigDecimal amount, BigDecimal pct, int scale) {
        return amount.multiply(pct).divide(BigDecimal.valueOf(100)).setScale(scale, RoundingMode.HALF_UP);
    }

    /** The line's whole amount, before its percent complete and its discount (see {@link #grossAmount(int)}). */
    private BigDecimal fullAmount(int scale) {
        if (tiers != null) {
            BigDecimal before = creditOfLine != null && creditedUnitsTop != null
                    ? creditedUnitsTop.subtract(invoice == null ? BigDecimal.ZERO : invoice.creditedUnitsBefore(this)).subtract(billedQty()).max(BigDecimal.ZERO)
                    : invoice == null ? BigDecimal.ZERO : invoice.tieredUnitsBefore(this);
            RateTiers bands = RateTiers.parse(tiers);
            if (overtime) {
                bands = new RateTiers(bands.bands().stream()
                        .map(b -> new RateTiers.Band(b.units(), charged(b.unitPrice()))).toList());
            }
            BigDecimal months = periodMonths();
            return months == null ? bands.charge(before, billedQty(), scale)
                    : bands.charge(before, billedQty(), 10).multiply(months).setScale(scale, RoundingMode.HALF_UP);
        }
        BigDecimal monthly = billedQty().multiply(chargedUnitPrice());
        BigDecimal months = periodMonths();
        // A service period is charged its share of the monthly price, rounded only once.
        return (months == null ? monthly : monthly.multiply(months)).setScale(scale, RoundingMode.HALF_UP);
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
