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
 * Each claim keeps the terms its due-by was fixed with, so a holiday added later can re-fix it. The terms of a claim
 * already fixed are read back from its due-by: the business days after its date up to it, skipping weekends and the
 * sheet's holidays as they are now.
 */
public class V59__claim_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        try (Statement alter = db.createStatement()) {
            alter.execute("ALTER TABLE claim ADD COLUMN terms INT NOT NULL DEFAULT 0");
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement select = db.createStatement();
                ResultSet rs = select.executeQuery("SELECT sheet_id, on_date FROM holiday")) {
            while (rs.next()) {
                holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getDate(2).toLocalDate());
            }
        }
        try (Statement select = db.createStatement();
                ResultSet rs = select.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = db.prepareStatement("UPDATE claim SET terms = ? WHERE id = ?")) {
            while (rs.next()) {
                Set<LocalDate> days = holidays.getOrDefault(rs.getString(2), Set.of());
                LocalDate date = rs.getDate(3).toLocalDate();
                LocalDate dueBy = rs.getDate(4).toLocalDate();
                int terms = 0;
                for (LocalDate day = date.plusDays(1); !day.isAfter(dueBy); day = day.plusDays(1)) {
                    if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY
                            && !days.contains(day)) {
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
