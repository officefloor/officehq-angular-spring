package net.officefloor.hq.app;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's duplicate window: the stored {@code duplicate_days} setting, the number of days before
 * today in which a sent invoice with the same fingerprint makes a client's draft a possible duplicate;
 * null when none is set (drafts are then never judged duplicates).
 */
@Service
public class DuplicateWindow {

    public static final String DUPLICATE_DAYS = "duplicate_days";

    private final JdbcTemplate jdbc;

    public DuplicateWindow(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Integer days() {
        List<String> days = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, DUPLICATE_DAYS);
        return days.isEmpty() ? null : Integer.valueOf(days.get(0).trim());
    }
}
