package net.officefloor.hq.app.claim;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

/**
 * Each claim keeps the terms its due-by was fixed with, so a holiday added later can fix it again. The terms of
 * a claim already on a sheet are read back from its due-by: the business days after its date up to it, over
 * the holidays on its sheet now.
 */
@Component
public class V59__claim_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection db = context.getConnection();
        try (Statement alter = db.createStatement()) {
            alter.execute("ALTER TABLE claim ADD COLUMN terms INT NOT NULL DEFAULT 0");
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (PreparedStatement select = db.prepareStatement("SELECT sheet_id, holiday_date FROM holiday");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                holidays.computeIfAbsent(rows.getString(1), k -> new HashSet<>()).add(rows.getDate(2).toLocalDate());
            }
        }
        try (PreparedStatement select = db.prepareStatement("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = db.prepareStatement("UPDATE claim SET terms = ? WHERE id = ?");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                Set<LocalDate> days = holidays.getOrDefault(rows.getString(2), Set.of());
                LocalDate dueBy = rows.getDate(4).toLocalDate();
                int terms = 0;
                for (LocalDate day = rows.getDate(3).toLocalDate().plusDays(1); !day.isAfter(dueBy);
                        day = day.plusDays(1)) {
                    DayOfWeek dow = day.getDayOfWeek();
                    if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !days.contains(day)) {
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
