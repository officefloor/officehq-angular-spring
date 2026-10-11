package net.officefloor.hq.app.clients;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * A unit type priced in progressive tiers, like tax brackets: bands of units each with its own unit
 * price, the last band (with no units) covering everything above. Stored as "units:unitPrice" joined
 * by semicolons, the last band with no units ("10:20.00;40:15.00;:12.00").
 */
public record RateTiers(List<Band> bands) {

    /** A band: its number of units (null for the last band, everything above) and their unit price. */
    public record Band(BigDecimal units, BigDecimal unitPrice) {
    }

    /** The tiers in the stored form; null when there are none. */
    public static RateTiers parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        List<Band> bands = new ArrayList<>();
        for (String band : stored.split(";")) {
            String[] parts = band.split(":", 2);
            bands.add(new Band(parts[0].isBlank() ? null : new BigDecimal(parts[0]), new BigDecimal(parts[1])));
        }
        return new RateTiers(bands);
    }

    /** The stored form of the tiers. */
    public String format() {
        return String.join(";", bands.stream()
                .map(b -> (b.units() == null ? "" : b.units().toPlainString()) + ":" + b.unitPrice().toPlainString())
                .toList());
    }

    /** The first band's unit price. */
    public BigDecimal firstPrice() {
        return bands.get(0).unitPrice();
    }

    /**
     * What the given quantity costs, starting from the first band: each band's units at that band's
     * price, rounded half up to the given number of decimals (2, the cent; 0 for whole yen).
     */
    public BigDecimal total(BigDecimal qty, int scale) {
        return charge(BigDecimal.ZERO, qty, scale);
    }

    /**
     * What the given quantity costs when the given number of units have already gone through the
     * bands before it: each of its units at the price of the band it falls in, the exact charge
     * rounded half up to the given number of decimals.
     */
    public BigDecimal charge(BigDecimal before, BigDecimal qty, int scale) {
        return exact(before.add(qty)).subtract(exact(before)).setScale(scale, RoundingMode.HALF_UP);
    }

    /** What the given quantity costs from the first band, unrounded. */
    private BigDecimal exact(BigDecimal qty) {
        BigDecimal left = qty;
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < bands.size() && left.signum() > 0; i++) {
            Band band = bands.get(i);
            boolean last = band.units() == null || i == bands.size() - 1;
            BigDecimal units = last ? left : left.min(band.units());
            total = total.add(units.multiply(band.unitPrice()));
            left = left.subtract(units);
        }
        return total;
    }
}
