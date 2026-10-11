package net.officefloor.hq.app;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The standard payment terms regions set, the {@code region_terms_days_<region>} settings: an invoice
 * for a client in such a region with no terms of their own is due that many days after it is issued
 * instead of at the end of the next month (see InvoiceService).
 */
@Service
public class RegionTerms {

    /** The prefix of the setting holding a region's terms, followed by the region (region_terms_days_NY). */
    public static final String REGION_TERMS_DAYS = "region_terms_days_";

    private final JdbcTemplate jdbc;

    public RegionTerms(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every region's terms in days, by region. */
    public Map<String, Integer> all() {
        Map<String, Integer> days = new TreeMap<>();
        jdbc.queryForList("SELECT name, setting_value FROM app_setting WHERE name LIKE ? ORDER BY name", REGION_TERMS_DAYS + "%")
                .forEach(r -> days.put(((String) r.get("NAME")).substring(REGION_TERMS_DAYS.length()),
                        Integer.valueOf((String) r.get("SETTING_VALUE"))));
        return days;
    }

    /** The region's terms in days; empty when the client has no region or the region sets none. */
    public Optional<Integer> forRegion(String region) {
        if (region == null) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?", String.class,
                REGION_TERMS_DAYS + region).stream().findFirst().map(Integer::valueOf);
    }

    /** Records the region's terms in days, replacing any it had. */
    public void set(String region, int days) {
        jdbc.update("DELETE FROM app_setting WHERE name = ?", REGION_TERMS_DAYS + region);
        jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)", REGION_TERMS_DAYS + region,
                Integer.toString(days));
    }
}
