package db.migration;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.claim.BusinessDays;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Terms count business days. A claim keeps the terms it was fixed with and the date they count from
 * (its date); its due-by is the terms-th business day after that date, skipping Saturdays, Sundays and
 * the holidays on its sheet. An existing claim's terms are the calendar days from its date to its
 * due-by, less the holidays its due-by was moved past; its due-by is worked out again from them.
 */
public class V53__claim_business_day_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("ALTER TABLE claim ADD COLUMN terms INT DEFAULT 0 NOT NULL");
            ddl.execute("ALTER TABLE claim ADD COLUMN due_from DATE");
            ddl.execute("UPDATE claim SET due_from = claim_date");
            ddl.execute("ALTER TABLE claim ALTER COLUMN due_from SET NOT NULL");
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT sheet_id, holiday_date FROM holiday")) {
            while (rows.next()) {
                holidays.computeIfAbsent(rows.getString(1), id -> new HashSet<>()).add(rows.getDate(2).toLocalDate());
            }
        }
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = connection.prepareStatement("UPDATE claim SET terms = ?, due_by = ? WHERE id = ?")) {
            while (rows.next()) {
                Set<LocalDate> days = holidays.getOrDefault(rows.getString(2), Set.of());
                LocalDate date = rows.getDate(3).toLocalDate();
                LocalDate dueBy = rows.getDate(4).toLocalDate();
                LocalDate moved = dueBy;
                while (moved.isAfter(date) && days.contains(moved.minusDays(1))) {
                    moved = moved.minusDays(1);
                }
                int terms = (int) Math.max(0, ChronoUnit.DAYS.between(date, moved));
                update.setInt(1, terms);
                update.setDate(2, Date.valueOf(BusinessDays.dueBy(date, terms, days)));
                update.setLong(3, rows.getLong(1));
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
