package db.migration;

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
 * A claim keeps the terms its due-by was fixed with, so a holiday added later can work its due-by
 * out again. For the claims already held they are read back from the due-by: the business days
 * from the claim's date to its due-by over the sheet's holidays as they are now.
 */
public class V59__claim_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement alter = context.getConnection().createStatement()) {
            alter.execute("ALTER TABLE claim ADD COLUMN terms INT");
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement select = context.getConnection().createStatement();
                ResultSet rs = select.executeQuery("SELECT sheet_id, holiday_date FROM holiday")) {
            while (rs.next()) {
                holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getDate(2).toLocalDate());
            }
        }
        try (Statement select = context.getConnection().createStatement();
                ResultSet rs = select.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = context.getConnection()
                        .prepareStatement("UPDATE claim SET terms = ? WHERE id = ?")) {
            while (rs.next()) {
                Set<LocalDate> off = holidays.getOrDefault(rs.getString(2), Set.of());
                LocalDate dueBy = rs.getDate(4).toLocalDate();
                int terms = 0;
                for (LocalDate d = rs.getDate(3).toLocalDate().plusDays(1); !d.isAfter(dueBy); d = d.plusDays(1)) {
                    if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY
                            && !off.contains(d)) {
                        terms++;
                    }
                }
                update.setInt(1, terms);
                update.setLong(2, rs.getLong(1));
                update.addBatch();
            }
            update.executeBatch();
        }
        try (Statement alter = context.getConnection().createStatement()) {
            alter.execute("ALTER TABLE claim ALTER COLUMN terms SET NOT NULL");
        }
    }
}
