package net.officefloor.hq.app;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's "today": the stored {@code as_of} setting when there is one (a test's fixed
 * date), otherwise the real date. Everything date-dependent asks this instead of the system clock.
 */
@Service
public class Today {

    static final String AS_OF = "as_of";

    private final JdbcTemplate jdbc;

    public Today(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LocalDate date() {
        List<String> asOf = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, AS_OF);
        return asOf.isEmpty() ? LocalDate.now() : LocalDate.parse(asOf.get(0));
    }
}
