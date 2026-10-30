package net.officefloor.hq.app.claim;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.holiday.HolidayService;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

/**
 * Terms count business days: every claim's due-by becomes the terms-th business day after its date, skipping
 * weekends and the holidays on its sheet now. The terms a claim was fixed with are read back from its due-by:
 * the days from its date to it, less the holidays it was moved past (the run of holidays just before it).
 */
@Component
public class V53__business_day_due_by extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection db = context.getConnection();
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (PreparedStatement select = db.prepareStatement("SELECT sheet_id, holiday_date FROM holiday");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                holidays.computeIfAbsent(rows.getString(1), k -> new HashSet<>()).add(rows.getDate(2).toLocalDate());
            }
        }
        try (PreparedStatement select = db.prepareStatement("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = db.prepareStatement("UPDATE claim SET due_by = ? WHERE id = ?");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                Set<LocalDate> days = holidays.getOrDefault(rows.getString(2), Set.of());
                LocalDate date = rows.getDate(3).toLocalDate();
                LocalDate dueBy = rows.getDate(4).toLocalDate();
                LocalDate unmoved = dueBy;
                while (unmoved.isAfter(date) && days.contains(unmoved.minusDays(1))) {
                    unmoved = unmoved.minusDays(1);
                }
                int terms = (int) Math.max(0, ChronoUnit.DAYS.between(date, unmoved));
                update.setDate(1, Date.valueOf(HolidayService.businessDaysAfter(date, terms, days)));
                update.setLong(2, rows.getLong(1));
                update.executeUpdate();
            }
        }
    }
}
