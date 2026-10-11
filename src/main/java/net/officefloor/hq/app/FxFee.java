package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's conversion fee: the stored {@code fx_fee_pct} setting, a percentage such as 1.5 passed
 * on to a client paying in a currency other than their own; 0 when none is set. Part of such a payment is
 * the fee: that percentage of the amount converted into the client's currency, rounded half up.
 */
@Service
public class FxFee {

    public static final String FX_FEE_PCT = "fx_fee_pct";

    private final JdbcTemplate jdbc;

    public FxFee(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal percent() {
        List<String> rate = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, FX_FEE_PCT);
        return rate.isEmpty() ? BigDecimal.ZERO : new BigDecimal(rate.get(0));
    }

    /** The fee on the converted amount, rounded half up to the scale (2 for cents, 0 for yen). */
    public BigDecimal of(BigDecimal converted, int scale) {
        return converted.multiply(percent()).movePointLeft(2).setScale(scale, RoundingMode.HALF_UP);
    }
}
