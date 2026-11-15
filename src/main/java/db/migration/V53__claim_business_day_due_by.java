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
 * Terms count business days: a claim's due-by is the terms-th business day after its date, skipping
 * every Saturday, Sunday and holiday on the sheet. The claims already on a sheet take that due-by,
 * each with the terms it was fixed with and the holidays on the sheet now. The terms are recovered
 * from the due-by as it was fixed: its date plus the terms, moved past any holidays it fell on.
 */
public class V53__claim_business_day_due_by extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement select = connection.createStatement();
                ResultSet rs = select.executeQuery("SELECT sheet_id, holiday_date FROM sheet_holiday")) {
            while (rs.next()) {
                holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getDate(2).toLocalDate());
            }
        }
        try (Statement select = connection.createStatement();
                ResultSet rs = select.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = connection.prepareStatement("UPDATE claim SET due_by = ? WHERE id = ?")) {
            while (rs.next()) {
                Set<LocalDate> sheetHolidays = holidays.getOrDefault(rs.getString(2), Set.of());
                LocalDate date = rs.getDate(3).toLocalDate();
                LocalDate fixed = rs.getDate(4).toLocalDate();
                // the day the terms reached, before the due-by was moved past holidays
                LocalDate reached = fixed;
                while (reached.isAfter(date) && sheetHolidays.contains(reached.minusDays(1))) {
                    reached = reached.minusDays(1);
                }
                int terms = (int) Math.max(0, ChronoUnit.DAYS.between(date, reached));
                LocalDate due = BusinessDays.after(date, terms, sheetHolidays);
                if (!due.equals(fixed)) {
                    update.setDate(1, Date.valueOf(due));
                    update.setLong(2, rs.getLong(1));
                    update.executeUpdate();
                }
            }
        }
    }
}
