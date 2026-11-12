package db.migration;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.holiday.HolidayService;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Terms count business days: every claim's due-by becomes the terms-th business day after its date, skipping
 * Saturdays, Sundays and the sheet's holidays as they stand now. The terms a claim was fixed with are its
 * due-by less its date, less the run of the sheet's holidays it was moved past.
 */
public class V53__claim_due_by_business_days extends BaseJavaMigration {

    private record Row(long id, String sheetId, LocalDate date, LocalDate dueBy) {
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT sheet_id, holiday_date FROM holiday")) {
            while (rs.next()) {
                holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getDate(2).toLocalDate());
            }
        }
        List<Row> rows = new ArrayList<>();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim")) {
            while (rs.next()) {
                rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(),
                        rs.getDate(4).toLocalDate()));
            }
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE claim SET due_by = ? WHERE id = ?")) {
            for (Row row : rows) {
                Set<LocalDate> days = holidays.getOrDefault(row.sheetId(), Set.of());
                LocalDate unmoved = row.dueBy();
                while (unmoved.isAfter(row.date()) && days.contains(unmoved.minusDays(1))) {
                    unmoved = unmoved.minusDays(1);
                }
                int terms = (int) Math.max(0, ChronoUnit.DAYS.between(row.date(), unmoved));
                update.setDate(1, Date.valueOf(HolidayService.businessDaysAfter(row.date(), terms, days)));
                update.setLong(2, row.id());
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
