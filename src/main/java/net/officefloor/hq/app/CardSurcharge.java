package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's card surcharge: the stored {@code card_surcharge_pct} setting, a percentage such as
 * 2.5 passed on to a client paying by card; 0 when none is set. Part of a card payment is the
 * surcharge: the amount less the amount divided by 1 plus the rate, rounded half up to the cent.
 */
@Service
public class CardSurcharge {

    public static final String CARD_SURCHARGE_PCT = "card_surcharge_pct";

    private final JdbcTemplate jdbc;

    public CardSurcharge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal percent() {
        List<String> rate = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, CARD_SURCHARGE_PCT);
        return rate.isEmpty() ? BigDecimal.ZERO : new BigDecimal(rate.get(0));
    }

    /** The surcharge in the amount paid by card, rounded half up to the scale (2 for cents, 0 for yen). */
    public BigDecimal of(BigDecimal amount, int scale) {
        BigDecimal rate = percent();
        if (rate.signum() == 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        BigDecimal factor = BigDecimal.ONE.add(rate.movePointLeft(2));
        BigDecimal net = amount.divide(factor, MathContext.DECIMAL128);
        return amount.subtract(net).setScale(scale, RoundingMode.HALF_UP);
    }
}
