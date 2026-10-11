package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's tax rate: the stored {@code tax_rate} setting, a percentage such as 10. With no
 * rate set there is no tax, so the rate is 0.
 */
@Service
public class TaxRate {

    static final String TAX_RATE = "tax_rate";

    private final JdbcTemplate jdbc;

    public TaxRate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal percent() {
        List<String> rate = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, TAX_RATE);
        return rate.isEmpty() ? BigDecimal.ZERO : new BigDecimal(rate.get(0));
    }
}
