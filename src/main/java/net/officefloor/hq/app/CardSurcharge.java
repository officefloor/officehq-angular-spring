package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.clients.Currency;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's card surcharge: the stored {@code card_surcharge_pct} setting, a percentage such as
 * 2.5 passed on to a client paying by card; 0 when none is set. Part of a card payment is the
 * surcharge: the amount less the amount divided by 1 plus the rate, rounded half up to the cent.
 * The surcharge on one payment is never more than the {@code card_surcharge_cap} setting, in US dollars
 * converted to the payment's currency at the payment date's rate and rounded down; no cap when none is set.
 */
@Service
public class CardSurcharge {

    public static final String CARD_SURCHARGE_PCT = "card_surcharge_pct";

    public static final String CARD_SURCHARGE_CAP = "card_surcharge_cap";

    private final JdbcTemplate jdbc;

    private final ExchangeRates exchangeRates;

    public CardSurcharge(JdbcTemplate jdbc, ExchangeRates exchangeRates) {
        this.jdbc = jdbc;
        this.exchangeRates = exchangeRates;
    }

    /** The most surcharge on one payment, in US dollars; empty when there is no cap. */
    public Optional<BigDecimal> cap() {
        return jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?", String.class, CARD_SURCHARGE_CAP)
                .stream().findFirst().map(v -> new BigDecimal(v.trim()));
    }

    public BigDecimal percent() {
        List<String> rate = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, CARD_SURCHARGE_PCT);
        return rate.isEmpty() ? BigDecimal.ZERO : new BigDecimal(rate.get(0));
    }

    /**
     * The surcharge in the amount paid by card in the currency on the date, rounded half up to the
     * currency's scale (2 for cents, 0 for yen), and no more than the cap converted on that date.
     */
    public BigDecimal of(BigDecimal amount, Currency currency, LocalDate on) {
        int scale = currency.scale();
        BigDecimal rate = percent();
        if (rate.signum() == 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        BigDecimal factor = BigDecimal.ONE.add(rate.movePointLeft(2));
        BigDecimal net = amount.divide(factor, MathContext.DECIMAL128);
        BigDecimal surcharge = amount.subtract(net).setScale(scale, RoundingMode.HALF_UP);
        return cap().map(usd -> exchangeRates.convert(usd, Currency.USD, currency, on))
                .filter(cap -> cap.compareTo(surcharge) < 0).orElse(surcharge);
    }
}
