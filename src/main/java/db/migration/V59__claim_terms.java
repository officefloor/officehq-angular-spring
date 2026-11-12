package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * The terms a claim's due-by was fixed with: kept on the claim so its due-by can be worked out again when a
 * holiday is added. An existing claim's are the business days from the day after its date through its due-by,
 * skipping Saturdays, Sundays and the sheet's holidays as they stand now.
 */
public class V59__claim_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE claim ADD COLUMN terms INT NOT NULL DEFAULT 0");
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT sheet_id, holiday_date FROM holiday")) {
            while (rs.next()) {
                holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getDate(2).toLocalDate());
            }
        }
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = connection.prepareStatement("UPDATE claim SET terms = ? WHERE id = ?")) {
            while (rs.next()) {
                Set<LocalDate> days = holidays.getOrDefault(rs.getString(2), Set.of());
                LocalDate due = rs.getDate(4).toLocalDate();
                int terms = 0;
                for (LocalDate d = rs.getDate(3).toLocalDate().plusDays(1); !d.isAfter(due); d = d.plusDays(1)) {
                    if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY
                            && !days.contains(d)) {
                        terms++;
                    }
                }
                update.setInt(1, terms);
                update.setLong(2, rs.getLong(1));
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
