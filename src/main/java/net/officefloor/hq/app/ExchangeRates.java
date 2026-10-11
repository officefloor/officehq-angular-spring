package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.clients.Currency;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's exchange rates: the US dollars one unit of each currency is worth, stored as the
 * {@code usd_per_<code>} settings. A US dollar is worth one; a currency with no rate set is taken
 * at par. Dated rates (the {@code fx_rate} table) take over from their date on: something converted on
 * a date uses the latest dated rate on or before it, or the standing rate before any. Invoices read
 * their client's rates themselves (see Invoice).
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

    /** A dated rate: from the date on, the US dollars one unit of the currency is worth. */
    public record Dated(LocalDate from, Currency currency, BigDecimal usdPer) {
    }

    /** Every dated rate, by date then currency. */
    public List<Dated> dated() {
        return jdbc.query("SELECT from_date, currency, usd_per FROM fx_rate ORDER BY from_date, currency, id",
                (rs, n) -> new Dated(rs.getDate(1).toLocalDate(), Currency.of(rs.getString(2)), rs.getBigDecimal(3)));
    }

    /** Adds a dated rate. */
    public void addDated(LocalDate from, Currency currency, BigDecimal usdPer) {
        jdbc.update("INSERT INTO fx_rate (from_date, currency, usd_per) VALUES (?, ?, ?)", Date.valueOf(from),
                currency.name(), usdPer);
    }

    /**
     * The US dollars one unit of the currency is worth on the date: the latest dated rate on or before
     * it, otherwise the standing rate (see {@link #usdPer(Currency)}).
     */
    public BigDecimal usdPer(Currency currency, LocalDate on) {
        if (currency == Currency.USD) {
            return BigDecimal.ONE;
        }
        return jdbc.queryForList("SELECT usd_per FROM fx_rate WHERE currency = ? AND from_date <= ? ORDER BY from_date DESC, id DESC LIMIT 1",
                BigDecimal.class, currency.name(), Date.valueOf(on)).stream().findFirst().orElseGet(() -> usdPer(currency));
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
     * unit paid) / (dollars per unit of the other), at the rates in force on the date, rounded half up
     * to the other's step.
     */
    public BigDecimal convert(BigDecimal amount, Currency from, Currency to, LocalDate on) {
        if (from == to) {
            return amount.setScale(to.scale());
        }
        return to.roundToStep(amount.multiply(usdPer(from, on)).divide(usdPer(to, on), MathContext.DECIMAL128));
    }
}
