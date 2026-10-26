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
import net.officefloor.hq.app.holiday.HolidayService;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Terms count business days: every claim's due-by becomes the terms-th business day after its date, skipping
 * weekends and the sheet's holidays as they are now. The terms each claim was fixed with are read back from its
 * due-by: its calendar days after the claim's date, less the run of holidays just before it that it was moved past.
 */
public class V53__claim_due_by_business_days extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement select = db.createStatement();
                ResultSet rs = select.executeQuery("SELECT sheet_id, on_date FROM holiday")) {
            while (rs.next()) {
                holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getDate(2).toLocalDate());
            }
        }
        try (Statement select = db.createStatement();
                ResultSet rs = select.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = db.prepareStatement("UPDATE claim SET due_by = ? WHERE id = ?")) {
            while (rs.next()) {
                Set<LocalDate> days = holidays.getOrDefault(rs.getString(2), Set.of());
                LocalDate date = rs.getDate(3).toLocalDate();
                LocalDate fixed = rs.getDate(4).toLocalDate();
                while (fixed.minusDays(1).compareTo(date) >= 0 && days.contains(fixed.minusDays(1))) {
                    fixed = fixed.minusDays(1);
                }
                int terms = (int) Math.max(0, ChronoUnit.DAYS.between(date, fixed));
                update.setDate(1, Date.valueOf(HolidayService.businessDaysAfter(date, terms, days)));
                update.setLong(2, rs.getLong(1));
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
