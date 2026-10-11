package net.officefloor.hq.app.clients;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** An entry of a client's rate card: a single unit price, or progressive tiers (see {@link RateTiers}). */
@Embeddable
public class ClientRate {

    /** The single unit price; null when priced in tiers. */
    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    /** The tiers in their stored form; null for a single unit price. */
    @Column(name = "tiers")
    private String tiers;

    /** The increment the unit type is billed in (0.25 of an hour, packs of 5); null for none. */
    @Column(name = "increment")
    private BigDecimal increment;

    /** The minimum units the unit type is billed (a call-out is at least 2 hours); null for none. */
    @Column(name = "min_units")
    private BigDecimal minUnits;

    /**
     * The unit type's own tax percentage, charged on its lines instead of the client's regional or workspace
     * rate unless a line has a rate of its own; null for none.
     */
    @Column(name = "tax_rate")
    private BigDecimal taxRate;

    protected ClientRate() {
    }

    /** A single unit price, billed in the given increment and minimum units (null for none). */
    public ClientRate(BigDecimal unitPrice, BigDecimal increment, BigDecimal minUnits) {
        this.unitPrice = unitPrice;
        this.increment = increment;
        this.minUnits = minUnits;
    }

    /** A single unit price, billed in the given increment and minimum units and taxed at the given rate (null for none). */
    public ClientRate(BigDecimal unitPrice, BigDecimal increment, BigDecimal minUnits, BigDecimal taxRate) {
        this(unitPrice, increment, minUnits);
        this.taxRate = taxRate;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getIncrement() {
        return increment;
    }

    public BigDecimal getMinUnits() {
        return minUnits;
    }

    /** The unit type's own tax percentage; null for none. */
    public BigDecimal getTaxRate() {
        return taxRate;
    }

    /** The tiers; null for a single unit price. */
    public RateTiers tiers() {
        return RateTiers.parse(tiers);
    }
}
