package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's tax rate: the stored {@code tax_rate} setting, a percentage such as 10. With no
 * rate set there is no tax, so the rate is 0. A region can have its own rate, the
 * {@code region_tax_rate_<region>} setting, charged to the region's clients instead. A region's rate
 * can change on a date (the {@code tax_rate_change} table): an invoice is taxed at the latest change on
 * or before its issue date, or the region's own rate before any. Invoices read the changes themselves
 * (see Invoice).
 */
@Service
public class TaxRate {

    static final String TAX_RATE = "tax_rate";

    /** The prefix of the setting holding a region's rate, followed by the region (region_tax_rate_NY). */
    public static final String REGION_TAX_RATE = "region_tax_rate_";

    /** The setting holding the workspace's home region: registered clients outside it are reverse charged. */
    public static final String HOME_REGION = "home_region";

    private final JdbcTemplate jdbc;

    public TaxRate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal percent() {
        List<String> rate = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, TAX_RATE);
        return rate.isEmpty() ? BigDecimal.ZERO : new BigDecimal(rate.get(0));
    }

    /** The region's own rate, as stored; null when the client has no region or the region has none listed. */
    public String forRegion(String region) {
        if (region == null) {
            return null;
        }
        return jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?", String.class,
                REGION_TAX_RATE + region).stream().findFirst().orElse(null);
    }

    /** Every region's own rate, by region. */
    public Map<String, BigDecimal> regions() {
        Map<String, BigDecimal> rates = new TreeMap<>();
        jdbc.queryForList("SELECT name, setting_value FROM app_setting WHERE name LIKE ? ORDER BY name", REGION_TAX_RATE + "%")
                .forEach(r -> rates.put(((String) r.get("NAME")).substring(REGION_TAX_RATE.length()),
                        new BigDecimal((String) r.get("SETTING_VALUE"))));
        return rates;
    }

    /** The workspace's home region; null when none is set. */
    public String homeRegion() {
        return jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?", String.class, HOME_REGION)
                .stream().findFirst().orElse(null);
    }

    /** A dated change of a region's rate: from the date on, the region is taxed at the rate. */
    public record Change(String region, LocalDate from, BigDecimal rate) {
    }

    /** Every dated change, by region then date. */
    public List<Change> changes() {
        return jdbc.query("SELECT region, from_date, rate FROM tax_rate_change ORDER BY region, from_date, id",
                (rs, n) -> new Change(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3)));
    }

    /** Records that the region's rate changes to the given rate from the date on. */
    public void addChange(String region, LocalDate from, BigDecimal rate) {
        jdbc.update("INSERT INTO tax_rate_change (region, from_date, rate) VALUES (?, ?, ?)", region,
                Date.valueOf(from), rate);
    }

    /**
     * The region's dated changes as "from date=rate" joined by commas in date order, the form invoices
     * read them in; null when the client has no region or the region has no changes.
     */
    public String changesFor(String region) {
        if (region == null) {
            return null;
        }
        List<String> changes = jdbc.query("SELECT from_date, rate FROM tax_rate_change WHERE region = ? ORDER BY from_date, id",
                (rs, n) -> rs.getDate(1).toLocalDate() + "=" + rs.getBigDecimal(2).toPlainString(), region);
        return changes.isEmpty() ? null : String.join(",", changes);
    }
}
