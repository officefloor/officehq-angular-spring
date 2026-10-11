package net.officefloor.hq.app.clients;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * A currency a client is billed in, with the symbol its amounts are shown with and how many decimals
 * they have: yen have none, so every amount for a yen client is in whole yen.
 */
public enum Currency {
    USD("$", 2), EUR("€", 2), GBP("£", 2), JPY("¥", 0), CHF("CHF ", 2);

    private final String symbol;
    private final int scale;

    Currency(String symbol, int scale) {
        this.symbol = symbol;
        this.scale = scale;
    }

    /** How many decimals an amount has: 2 (to the cent), or 0 for yen. */
    public int scale() {
        return scale;
    }

    /** Whether the amount is whole in this currency: no more decimals than {@link #scale()}. */
    public boolean isWhole(BigDecimal amount) {
        return amount.stripTrailingZeros().scale() <= scale;
    }

    /** The amount with its symbol and thousands separators, e.g. €1,234.50 or ¥1,235. */
    public String format(BigDecimal amount) {
        DecimalFormat number = new DecimalFormat(scale == 0 ? "#,##0" : "#,##0.00",
                DecimalFormatSymbols.getInstance(Locale.US));
        BigDecimal rounded = amount.setScale(scale, RoundingMode.HALF_UP);
        return (rounded.signum() < 0 ? "-" : "") + symbol + number.format(rounded.abs());
    }

    /** The currency with the code, or null when there is no such currency. */
    public static Currency of(String code) {
        for (Currency c : values()) {
            if (c.name().equals(code)) {
                return c;
            }
        }
        return null;
    }

    /** The currency with the code; US dollars when there is none. */
    public static Currency orUsd(String code) {
        Currency c = code == null ? null : of(code);
        return c == null ? USD : c;
    }
}
