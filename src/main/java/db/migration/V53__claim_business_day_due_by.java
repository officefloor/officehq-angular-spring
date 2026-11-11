package db.migration;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * Terms count business days. Every claim's due-by is worked out again as the terms-th business day after its date,
 * skipping Saturdays, Sundays and the sheet's holidays now. The terms a claim was fixed with are the days from its date
 * to its due-by, less the holidays its due-by was moved past (the holidays just before it, after its date).
 */
public class V53__claim_business_day_due_by extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT sheet_id, holiday_date FROM holiday");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                holidays.computeIfAbsent(rows.getString(1), k -> new HashSet<>()).add(rows.getDate(2).toLocalDate());
            }
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = connection.prepareStatement("UPDATE claim SET due_by = ? WHERE id = ?");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                Set<LocalDate> sheetHolidays = holidays.getOrDefault(rows.getString(2), Set.of());
                LocalDate date = rows.getDate(3).toLocalDate();
                LocalDate dueBy = rows.getDate(4).toLocalDate();
                LocalDate fixedFrom = dueBy;
                while (fixedFrom.isAfter(date) && sheetHolidays.contains(fixedFrom.minusDays(1))) {
                    fixedFrom = fixedFrom.minusDays(1);
                }
                int terms = (int) Math.max(0, ChronoUnit.DAYS.between(date, fixedFrom));
                update.setDate(1, Date.valueOf(HolidayService.businessDaysAfter(sheetHolidays, date, terms)));
                update.setLong(2, rows.getLong(1));
                update.executeUpdate();
            }
        }
    }
}
