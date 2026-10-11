package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
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
}
