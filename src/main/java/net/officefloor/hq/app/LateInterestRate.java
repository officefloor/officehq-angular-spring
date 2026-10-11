package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's annual late-interest rate: the stored {@code late_interest_rate} setting, a
 * percentage such as 12. With no rate set no late interest is charged, so the rate is 0.
 */
@Service
public class LateInterestRate {

    static final String LATE_INTEREST_RATE = "late_interest_rate";

    private final JdbcTemplate jdbc;

    public LateInterestRate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal percent() {
        List<String> rate = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, LATE_INTEREST_RATE);
        return rate.isEmpty() ? BigDecimal.ZERO : new BigDecimal(rate.get(0));
    }
}
