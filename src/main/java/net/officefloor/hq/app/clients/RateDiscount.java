package net.officefloor.hq.app.clients;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A client's discount on rates: a percentage taken off every price a line takes from the client's rate card or the
 * price list, each band's price included, rounded half up per unit to the cent (whole yen), after converting a price
 * list price into the client's currency and before any overtime. A price typed on a line is not reduced.
 */
public final class RateDiscount {

    private RateDiscount() {
    }

    /** The unit price less the percentage (none when null or 0), rounded half up to the currency's decimals. */
    public static BigDecimal price(BigDecimal unitPrice, BigDecimal pct, Currency currency) {
        if (unitPrice == null || pct == null || pct.signum() == 0) {
            return unitPrice;
        }
        return unitPrice.multiply(BigDecimal.valueOf(100).subtract(pct)).movePointLeft(2)
                .setScale(currency.scale(), RoundingMode.HALF_UP);
    }

    /** The tiers (in their stored form, null for none) with each band's price less the percentage. */
    public static String tiers(String tiers, BigDecimal pct, Currency currency) {
        RateTiers parsed = RateTiers.parse(tiers);
        if (parsed == null || pct == null || pct.signum() == 0) {
            return tiers;
        }
        return new RateTiers(parsed.bands().stream()
                .map(b -> new RateTiers.Band(b.units(), price(b.unitPrice(), pct, currency))).toList()).format();
    }
}
