package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.claim.BusinessDays;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * A claim keeps the terms its due-by was fixed with, so a holiday added later re-fixes it from its
 * date. The claims already on a sheet take the business days from their date to their due-by, over
 * the holidays on the sheet now.
 */
public class V59__claim_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement alter = connection.createStatement()) {
            alter.execute("ALTER TABLE claim ADD COLUMN terms INT DEFAULT 0 NOT NULL");
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement select = connection.createStatement();
                ResultSet rs = select.executeQuery("SELECT sheet_id, holiday_date FROM sheet_holiday")) {
            while (rs.next()) {
                holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getDate(2).toLocalDate());
            }
        }
        try (Statement select = connection.createStatement();
                ResultSet rs = select.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = connection.prepareStatement("UPDATE claim SET terms = ? WHERE id = ?")) {
            while (rs.next()) {
                Set<LocalDate> sheetHolidays = holidays.getOrDefault(rs.getString(2), Set.of());
                LocalDate date = rs.getDate(3).toLocalDate();
                LocalDate dueBy = rs.getDate(4).toLocalDate();
                int terms = 0;
                for (LocalDate day = date.plusDays(1); !day.isAfter(dueBy); day = day.plusDays(1)) {
                    if (BusinessDays.isBusinessDay(day, sheetHolidays)) {
                        terms++;
                    }
                }
                update.setInt(1, terms);
                update.setLong(2, rs.getLong(1));
                update.executeUpdate();
            }
        }
    }
}
