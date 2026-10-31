package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Each claim keeps the terms its due-by was fixed with, so adding a holiday can fix its due-by again. A claim
 *  already made is given the number of business days from its date to its due-by: counting from the day after
 *  its date, skipping Saturdays, Sundays and the sheet's holidays as they are now. */
public class V58__claim_terms extends BaseJavaMigration {

    private record ClaimRow(long id, String sheetId, LocalDate date, LocalDate dueBy) {
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection db = context.getConnection();
        try (Statement alter = db.createStatement()) {
            alter.execute("ALTER TABLE claim ADD COLUMN terms INTEGER");
        }
        List<ClaimRow> claims = new ArrayList<>();
        try (PreparedStatement select = db.prepareStatement("SELECT id, sheet_id, claim_date, due_by FROM claim");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                claims.add(new ClaimRow(rows.getLong(1), rows.getString(2), rows.getDate(3).toLocalDate(),
                        rows.getDate(4).toLocalDate()));
            }
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (PreparedStatement select = db.prepareStatement("SELECT sheet_id, holiday_date FROM holiday");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                holidays.computeIfAbsent(rows.getString(1), k -> new HashSet<>()).add(rows.getDate(2).toLocalDate());
            }
        }
        try (PreparedStatement update = db.prepareStatement("UPDATE claim SET terms = ? WHERE id = ?")) {
            for (ClaimRow claim : claims) {
                Set<LocalDate> sheetHolidays = holidays.getOrDefault(claim.sheetId(), Set.of());
                int terms = 0;
                for (LocalDate day = claim.date().plusDays(1); !day.isAfter(claim.dueBy()); day = day.plusDays(1)) {
                    DayOfWeek weekday = day.getDayOfWeek();
                    if (weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY && !sheetHolidays.contains(day)) {
                        terms++;
                    }
                }
                update.setInt(1, terms);
                update.setLong(2, claim.id());
                update.addBatch();
            }
            update.executeBatch();
        }
        try (Statement alter = db.createStatement()) {
            alter.execute("ALTER TABLE claim ALTER COLUMN terms SET NOT NULL");
        }
    }
}
