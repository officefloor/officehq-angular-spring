package net.officefloor.hq.app.clients;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An entry of a client's rate card: a single unit price, or progressive tiers (see {@link RateTiers}), for a
 * unit type from a date (or always).
 */
@Embeddable
public class ClientRate {

    /** The unit type (hour, day, ...) the entry prices. */
    @Column(name = "unit_type")
    private String unitType;

    /** The date the entry takes effect from; null for one that has always applied. */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

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

    /**
     * A single unit price for the unit type from the date (null for always), billed in the given increment and
     * minimum units (null for none).
     */
    public ClientRate(String unitType, LocalDate effectiveFrom, BigDecimal unitPrice, BigDecimal increment, BigDecimal minUnits) {
        this.unitType = unitType;
        this.effectiveFrom = effectiveFrom;
        this.unitPrice = unitPrice;
        this.increment = increment;
        this.minUnits = minUnits;
    }

    /**
     * A single unit price for the unit type from the date (null for always), billed in the given increment and
     * minimum units and taxed at the given rate (null for none).
     */
    public ClientRate(String unitType, LocalDate effectiveFrom, BigDecimal unitPrice, BigDecimal increment, BigDecimal minUnits,
            BigDecimal taxRate) {
        this(unitType, effectiveFrom, unitPrice, increment, minUnits);
        this.taxRate = taxRate;
    }

    public String getUnitType() {
        return unitType;
    }

    /** The date the entry takes effect from; null for always. */
    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    /** Whether the entry is for the unit type, matched ignoring case and surrounding spaces. */
    public boolean isFor(String type) {
        return type != null && unitType.equalsIgnoreCase(type.trim());
    }

    /** Whether the entry has taken effect by the date. */
    public boolean inForceOn(LocalDate on) {
        return effectiveFrom == null || !effectiveFrom.isAfter(on);
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
