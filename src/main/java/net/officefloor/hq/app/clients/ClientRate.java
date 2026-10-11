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

    protected ClientRate() {
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getIncrement() {
        return increment;
    }

    /** The tiers; null for a single unit price. */
    public RateTiers tiers() {
        return RateTiers.parse(tiers);
    }
}
