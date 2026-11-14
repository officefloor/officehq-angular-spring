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
 * Each claim keeps the terms in business days its due-by was fixed with, so a holiday added later can
 * re-fix it. For a claim already on a sheet they are the business days after its date up to and
 * including its due-by, skipping weekends and the holidays on its sheet now.
 */
public class V60__claim_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement alter = connection.createStatement()) {
            alter.execute("ALTER TABLE claim ADD COLUMN terms INT DEFAULT 0 NOT NULL");
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement select = connection.createStatement();
                ResultSet rs = select.executeQuery("SELECT sheet_id, holiday_date FROM holiday")) {
            while (rs.next()) {
                holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                        .add(rs.getObject(2, LocalDate.class));
            }
        }
        try (Statement select = connection.createStatement();
                ResultSet rs = select.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = connection.prepareStatement("UPDATE claim SET terms = ? WHERE id = ?")) {
            while (rs.next()) {
                Set<LocalDate> days = holidays.getOrDefault(rs.getString(2), Set.of());
                LocalDate dueBy = rs.getObject(4, LocalDate.class);
                int terms = 0;
                for (LocalDate day = rs.getObject(3, LocalDate.class).plusDays(1); !day.isAfter(dueBy);
                        day = day.plusDays(1)) {
                    DayOfWeek dow = day.getDayOfWeek();
                    if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !days.contains(day)) {
                        terms++;
                    }
                }
                if (terms != 0) {
                    update.setInt(1, terms);
                    update.setLong(2, rs.getLong(1));
                    update.executeUpdate();
                }
            }
        }
    }
}
