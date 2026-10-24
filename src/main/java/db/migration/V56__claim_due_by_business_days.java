package db.migration;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Works the due-by of every claim already kept out again in business days: the terms-th day after its
 * due-from that is not a Saturday, a Sunday or a holiday on its sheet. */
public class V56__claim_due_by_business_days extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Map<String, Set<LocalDate>> off = new HashMap<>();
        try (PreparedStatement select = context.getConnection()
                .prepareStatement("SELECT sheet_id, holiday_date FROM sheet_holiday");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                off.computeIfAbsent(rows.getString(1), sheet -> new HashSet<>()).add(rows.getDate(2).toLocalDate());
            }
        }
        try (PreparedStatement select = context.getConnection()
                .prepareStatement("SELECT id, sheet_id, due_from, terms FROM claim");
                PreparedStatement update = context.getConnection()
                        .prepareStatement("UPDATE claim SET due_by = ? WHERE id = ?");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                Set<LocalDate> holidays = off.getOrDefault(rows.getString(2), Set.of());
                LocalDate on = rows.getDate(3).toLocalDate();
                for (int counted = 0, terms = rows.getInt(4); counted < terms;) {
                    on = on.plusDays(1);
                    DayOfWeek day = on.getDayOfWeek();
                    if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(on)) counted++;
                }
                update.setDate(1, Date.valueOf(on));
                update.setLong(2, rows.getLong(1));
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
