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

    protected ClientRate() {
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /** The tiers; null for a single unit price. */
    public RateTiers tiers() {
        return RateTiers.parse(tiers);
    }
}
