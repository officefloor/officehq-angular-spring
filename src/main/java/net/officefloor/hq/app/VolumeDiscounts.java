package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's volume discounts (the {@code volume_discount} table): thresholds in US dollars, each
 * with a percentage. Invoices read them themselves (see Invoice#discount).
 */
@Service
public class VolumeDiscounts {

    private final JdbcTemplate jdbc;

    public VolumeDiscounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A threshold: a subtotal over this many US dollars gets the percentage off. */
    public record Threshold(BigDecimal over, BigDecimal pct) {
    }

    /** Every threshold, lowest first. */
    public List<Threshold> all() {
        return jdbc.query("SELECT over_usd, pct FROM volume_discount ORDER BY over_usd, id",
                (rs, n) -> new Threshold(rs.getBigDecimal(1), rs.getBigDecimal(2)));
    }

    /** Adds a threshold. */
    public void add(BigDecimal over, BigDecimal pct) {
        jdbc.update("INSERT INTO volume_discount (over_usd, pct) VALUES (?, ?)", over, pct);
    }
}
