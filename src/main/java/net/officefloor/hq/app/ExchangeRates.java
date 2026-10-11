package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;
import net.officefloor.hq.app.clients.Currency;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's exchange rates: the US dollars one unit of each currency is worth, stored as the
 * {@code usd_per_<code>} settings. A US dollar is worth one; a currency with no rate set is taken
 * at par. Invoices read their client's rate themselves (see Invoice).
 */
@Service
public class ExchangeRates {

    /** The prefix of the setting holding a currency's rate, followed by its code (usd_per_EUR). */
    public static final String USD_PER = "usd_per_";

    private final JdbcTemplate jdbc;

    public ExchangeRates(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every rate set, by currency code. */
    public Map<String, BigDecimal> all() {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        jdbc.queryForList("SELECT name, setting_value FROM app_setting WHERE name LIKE ? ORDER BY name", USD_PER + "%")
                .forEach(r -> rates.put(((String) r.get("NAME")).substring(USD_PER.length()),
                        new BigDecimal((String) r.get("SETTING_VALUE"))));
        return rates;
    }

    /** The US dollars one unit of the currency is worth: one for the dollar or a currency with no rate set. */
    public BigDecimal usdPer(Currency currency) {
        if (currency == Currency.USD) {
            return BigDecimal.ONE;
        }
        return jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?", String.class, USD_PER + currency.name())
                .stream().findFirst().map(v -> new BigDecimal(v.trim())).orElse(BigDecimal.ONE);
    }

    /**
     * The amount paid in one currency converted into another through US dollars: amount x (dollars per
     * unit paid) / (dollars per unit of the other), rounded half up to the other's step.
     */
    public BigDecimal convert(BigDecimal amount, Currency from, Currency to) {
        if (from == to) {
            return amount.setScale(to.scale());
        }
        return to.roundToStep(amount.multiply(usdPer(from)).divide(usdPer(to), MathContext.DECIMAL128));
    }
}
