package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's late fee switch: the stored {@code late_fee} setting, an amount in US dollars such as
 * 25. With any fee set, a late fee of 2% of what is owed (at least $10.00) is charged once on an invoice
 * (on a plan, once on each instalment) when it becomes overdue, whatever amount the setting holds. With
 * no fee set none is charged, so it is 0.
 */
@Service
public class LateFee {

    static final String LATE_FEE = "late_fee";

    private final JdbcTemplate jdbc;

    public LateFee(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal usd() {
        List<String> fee = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, LATE_FEE);
        return fee.isEmpty() ? BigDecimal.ZERO : new BigDecimal(fee.get(0));
    }
}
