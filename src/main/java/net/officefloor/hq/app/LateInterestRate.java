package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's annual late-interest rate: the stored {@code late_interest_rate} setting, a
 * percentage such as 12. With no rate set no late interest is charged, so the rate is 0. A region can
 * have a legal maximum annual rate, the {@code region_max_interest_<region>} setting: its clients are
 * charged a usual late-interest rate of no more than it, with any premiums (high risk, final notice)
 * added on top (invoices read the cap themselves, see Invoice).
 */
@Service
public class LateInterestRate {

    static final String LATE_INTEREST_RATE = "late_interest_rate";

    /** The prefix of the setting holding a region's legal maximum rate, followed by the region (region_max_interest_NY). */
    public static final String REGION_MAX_INTEREST = "region_max_interest_";

    private final JdbcTemplate jdbc;

    public LateInterestRate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal percent() {
        List<String> rate = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, LATE_INTEREST_RATE);
        return rate.isEmpty() ? BigDecimal.ZERO : new BigDecimal(rate.get(0));
    }

    /** Every region's legal maximum annual rate, by region. */
    public Map<String, BigDecimal> regionMaximums() {
        Map<String, BigDecimal> caps = new TreeMap<>();
        jdbc.queryForList("SELECT name, setting_value FROM app_setting WHERE name LIKE ? ORDER BY name", REGION_MAX_INTEREST + "%")
                .forEach(r -> caps.put(((String) r.get("NAME")).substring(REGION_MAX_INTEREST.length()),
                        new BigDecimal((String) r.get("SETTING_VALUE"))));
        return caps;
    }

    /** Records the region's legal maximum annual rate. */
    public void setRegionMaximum(String region, BigDecimal percent) {
        jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)", REGION_MAX_INTEREST + region,
                percent.toPlainString());
    }
}
