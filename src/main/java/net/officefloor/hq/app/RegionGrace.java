package net.officefloor.hq.app;

import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The grace periods regions set, the {@code region_grace_days_<region>} settings: a client in such a
 * region with no grace period of their own is given the region's instead of the standard week
 * (invoices read it themselves, see Invoice).
 */
@Service
public class RegionGrace {

    /** The prefix of the setting holding a region's grace period, followed by the region (region_grace_days_NY). */
    public static final String REGION_GRACE_DAYS = "region_grace_days_";

    private final JdbcTemplate jdbc;

    public RegionGrace(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every region's grace period in days, by region. */
    public Map<String, Integer> all() {
        Map<String, Integer> days = new TreeMap<>();
        jdbc.queryForList("SELECT name, setting_value FROM app_setting WHERE name LIKE ? ORDER BY name", REGION_GRACE_DAYS + "%")
                .forEach(r -> days.put(((String) r.get("NAME")).substring(REGION_GRACE_DAYS.length()),
                        Integer.valueOf((String) r.get("SETTING_VALUE"))));
        return days;
    }

    /** Records the region's grace period in days. */
    public void set(String region, int days) {
        jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)", REGION_GRACE_DAYS + region,
                Integer.toString(days));
    }
}
