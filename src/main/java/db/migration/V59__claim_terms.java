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
 * A claim keeps the terms its due-by was fixed with, so a holiday added later can work its due-by out again. The terms
 * of a claim already kept are the business days after its date up to its due-by, skipping Saturdays, Sundays and the
 * sheet's holidays now.
 */
public class V59__claim_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement alter = connection.createStatement()) {
            alter.execute("ALTER TABLE claim ADD COLUMN terms INT DEFAULT 0 NOT NULL");
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT sheet_id, holiday_date FROM holiday");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                holidays.computeIfAbsent(rows.getString(1), k -> new HashSet<>()).add(rows.getDate(2).toLocalDate());
            }
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = connection.prepareStatement("UPDATE claim SET terms = ? WHERE id = ?");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                Set<LocalDate> sheetHolidays = holidays.getOrDefault(rows.getString(2), Set.of());
                LocalDate day = rows.getDate(3).toLocalDate();
                LocalDate dueBy = rows.getDate(4).toLocalDate();
                int terms = 0;
                while (day.isBefore(dueBy)) {
                    day = day.plusDays(1);
                    if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY
                            && !sheetHolidays.contains(day)) {
                        terms++;
                    }
                }
                update.setInt(1, terms);
                update.setLong(2, rows.getLong(1));
                update.executeUpdate();
            }
        }
    }
}
