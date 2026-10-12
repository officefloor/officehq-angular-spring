package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import net.officefloor.hq.app.invoices.DunningLevel;

/**
 * The fees the workspace charges for its reminders, the {@code reminder_fee_<level>} settings: an amount in US
 * dollars charged on an invoice when the reminder of that dunning level goes out (invoices read them themselves,
 * see Invoice). A level with no fee set charges none.
 */
@Service
public class ReminderFees {

    /** The prefix of the setting holding a level's fee, followed by the level (reminder_fee_FINAL). */
    public static final String REMINDER_FEE = "reminder_fee_";

    private final JdbcTemplate jdbc;

    public ReminderFees(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every level's fee in US dollars, by level. */
    public Map<String, BigDecimal> all() {
        Map<String, BigDecimal> fees = new TreeMap<>();
        jdbc.queryForList("SELECT name, setting_value FROM app_setting WHERE name LIKE ? ORDER BY name", REMINDER_FEE + "%")
                .forEach(r -> fees.put(((String) r.get("NAME")).substring(REMINDER_FEE.length()),
                        new BigDecimal((String) r.get("SETTING_VALUE"))));
        return fees;
    }

    /** Sets the level's fee in US dollars, replacing any it had. */
    public void set(DunningLevel level, BigDecimal usd) {
        jdbc.update("DELETE FROM app_setting WHERE name = ?", REMINDER_FEE + level.name());
        jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)", REMINDER_FEE + level.name(),
                usd.toPlainString());
    }
}
