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
 * A claim keeps the terms its due-by was fixed with, so a holiday added later can fix it again. For an
 * existing claim they are the business days from its date to its due-by, over the sheet's holidays now
 * (none for a due-by on or before its date).
 */
public class V60__claim_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("ALTER TABLE claim ADD COLUMN terms INT NOT NULL DEFAULT 0");
        }

        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT sheet_id, holiday_date FROM holiday")) {
            while (rows.next()) {
                holidays.computeIfAbsent(rows.getString(1), k -> new HashSet<>()).add(rows.getDate(2).toLocalDate());
            }
        }

        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = connection.prepareStatement("UPDATE claim SET terms = ? WHERE id = ?")) {
            while (rows.next()) {
                Set<LocalDate> sheetHolidays = holidays.getOrDefault(rows.getString(2), Set.of());
                LocalDate date = rows.getDate(3).toLocalDate();
                LocalDate dueBy = rows.getDate(4).toLocalDate();
                int terms = 0;
                for (LocalDate d = date.plusDays(1); !d.isAfter(dueBy); d = d.plusDays(1)) {
                    DayOfWeek day = d.getDayOfWeek();
                    if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !sheetHolidays.contains(d)) terms++;
                }
                if (terms == 0) continue;
                update.setInt(1, terms);
                update.setLong(2, rows.getLong(1));
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
