package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.clients.Currency;
import net.officefloor.hq.app.clients.RateTiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace price list (the {@code price_list_rate} table): a price in US dollars per unit type,
 * a single price or tiers (see {@link RateTiers}), for unit types a client's own rate card doesn't
 * cover. A line priced from it gets each price, or each band's price, converted into the client's
 * currency at the rates on the invoice's issue date, rounded down to the currency's step.
 */
@Service
public class PriceList {

    private final JdbcTemplate jdbc;
    private final ExchangeRates exchangeRates;

    public PriceList(JdbcTemplate jdbc, ExchangeRates exchangeRates) {
        this.jdbc = jdbc;
        this.exchangeRates = exchangeRates;
    }

    /** An entry of the price list: its unit type and a single unit price or tiers, in US dollars. */
    public record Entry(String unitType, BigDecimal unitPrice, RateTiers tiers) {
    }

    /** A price converted into a client's currency: a single unit price, or tiers in their stored form. */
    public record Converted(BigDecimal unitPrice, String tiers) {
    }

    /** Every entry, by unit type. */
    public List<Entry> all() {
        return jdbc.query("SELECT unit_type, unit_price, tiers FROM price_list_rate ORDER BY unit_type",
                (rs, n) -> new Entry(rs.getString(1), rs.getBigDecimal(2), RateTiers.parse(rs.getString(3))));
    }

    /** Adds an entry: a single unit price, or tiers. */
    public void add(String unitType, BigDecimal unitPrice, RateTiers tiers) {
        jdbc.update("INSERT INTO price_list_rate (unit_type, unit_price, tiers) VALUES (?, ?, ?)", unitType,
                tiers == null ? unitPrice : null, tiers == null ? null : tiers.format());
    }

    /**
     * The price list's price for the unit type (matched ignoring case) converted into the currency at
     * the rates on the date, or null when the price list has none.
     */
    public Converted priceFor(String unitType, Currency currency, LocalDate on) {
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT unit_price, tiers FROM price_list_rate WHERE LOWER(unit_type) = LOWER(?)", unitType.trim());
        if (found.isEmpty()) {
            return null;
        }
        RateTiers tiers = RateTiers.parse((String) found.get(0).get("TIERS"));
        if (tiers != null) {
            RateTiers converted = new RateTiers(tiers.bands().stream()
                    .map(b -> new RateTiers.Band(b.units(), convert(b.unitPrice(), currency, on))).toList());
            return new Converted(converted.firstPrice(), converted.format());
        }
        return new Converted(convert((BigDecimal) found.get(0).get("UNIT_PRICE"), currency, on), null);
    }

    private BigDecimal convert(BigDecimal usd, Currency currency, LocalDate on) {
        return exchangeRates.convert(usd, Currency.USD, currency, on);
    }
}
