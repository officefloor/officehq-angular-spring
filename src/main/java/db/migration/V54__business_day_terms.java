package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.holiday.HolidayService;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Terms count business days: every claim's due-by (and every saved state of it in claim_history)
 * becomes the terms-th business day after its date, skipping weekends and the holidays on its sheet
 * now. The terms a claim was fixed with are those its due-by was worked out from: the days from its
 * date to its due-by, less the holidays directly before the due-by it was moved past.
 */
public class V54__business_day_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement select = connection.createStatement();
                ResultSet rs = select.executeQuery("SELECT sheet_id, holiday_date FROM holiday")) {
            while (rs.next()) {
                holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                        .add(rs.getObject(2, LocalDate.class));
            }
        }
        redo(connection, holidays, "claim");
        redo(connection, holidays, "claim_history");
    }

    private static void redo(Connection connection, Map<String, Set<LocalDate>> holidays, String table)
            throws Exception {
        try (Statement select = connection.createStatement();
                ResultSet rs = select.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM " + table);
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + table + " SET due_by = ? WHERE id = ?")) {
            while (rs.next()) {
                Set<LocalDate> days = holidays.getOrDefault(rs.getString(2), Set.of());
                LocalDate date = rs.getObject(3, LocalDate.class);
                LocalDate dueBy = rs.getObject(4, LocalDate.class);
                LocalDate fixed = dueBy;
                while (fixed.isAfter(date) && days.contains(fixed.minusDays(1))) {
                    fixed = fixed.minusDays(1);
                }
                int terms = (int) Math.max(0, ChronoUnit.DAYS.between(date, fixed));
                LocalDate business = HolidayService.businessDaysAfter(days, date, terms);
                if (!business.equals(dueBy)) {
                    update.setObject(1, business);
                    update.setLong(2, rs.getLong(1));
                    update.executeUpdate();
                }
            }
        }
    }
}
