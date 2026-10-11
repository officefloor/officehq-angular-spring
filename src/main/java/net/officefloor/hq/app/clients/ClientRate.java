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

    protected ClientRate() {
    }

    /** A single unit price, billed in the given increment and minimum units (null for none). */
    public ClientRate(BigDecimal unitPrice, BigDecimal increment, BigDecimal minUnits) {
        this.unitPrice = unitPrice;
        this.increment = increment;
        this.minUnits = minUnits;
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

    /** The tiers; null for a single unit price. */
    public RateTiers tiers() {
        return RateTiers.parse(tiers);
    }
}
