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
import net.officefloor.hq.app.holiday.BusinessDays;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Terms count business days. Each claim keeps the date its due-by counts from (due_from) and the terms
 * it was fixed with; its due-by is the terms-th business day after due_from, skipping every Saturday,
 * Sunday and holiday on the sheet. A claim already stored counts from its date, with the terms its fixed
 * due-by showed: the days from its date to its due-by, less the holidays its due-by was moved past.
 */
public class V46__claim_business_day_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        try (Statement ddl = db.createStatement()) {
            ddl.execute("ALTER TABLE claim ADD COLUMN due_from DATE");
            ddl.execute("ALTER TABLE claim ADD COLUMN terms INTEGER");
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement read = db.createStatement();
                ResultSet rows = read.executeQuery("SELECT sheet_id, holiday_date FROM holiday")) {
            while (rows.next()) {
                holidays.computeIfAbsent(rows.getString(1), (k) -> new HashSet<>())
                        .add(rows.getDate(2).toLocalDate());
            }
        }
        try (Statement read = db.createStatement();
                ResultSet rows = read.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement write = db.prepareStatement(
                        "UPDATE claim SET due_from = ?, terms = ?, due_by = ? WHERE id = ?")) {
            while (rows.next()) {
                Set<LocalDate> days = holidays.getOrDefault(rows.getString(2), Set.of());
                LocalDate date = rows.getDate(3).toLocalDate();
                LocalDate due = rows.getDate(4).toLocalDate();
                // Back past the holidays the due-by was moved over, to the date plus its terms.
                LocalDate target = due;
                while (target.isAfter(date) && days.contains(target.minusDays(1))) target = target.minusDays(1);
                int terms = (int) Math.max(0, ChronoUnit.DAYS.between(date, target));
                write.setDate(1, Date.valueOf(date));
                write.setInt(2, terms);
                write.setDate(3, Date.valueOf(BusinessDays.after(date, terms, days)));
                write.setLong(4, rows.getLong(1));
                write.addBatch();
            }
            write.executeBatch();
        }
        try (Statement ddl = db.createStatement()) {
            ddl.execute("ALTER TABLE claim ALTER COLUMN due_from SET NOT NULL");
            ddl.execute("ALTER TABLE claim ALTER COLUMN terms SET NOT NULL");
        }
    }
}
