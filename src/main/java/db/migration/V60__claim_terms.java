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
import net.officefloor.hq.app.holiday.BusinessDays;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Each claim keeps the terms its due-by was fixed with, so that adding a holiday can fix it again.
 * For a claim already on a sheet they are the business days after its date up to its due-by (over
 * the holidays on the sheet now), the due-by itself counting as the last of them; none when its
 * due-by is not after its date.
 */
public class V60__claim_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        try (Statement st = db.createStatement()) {
            st.execute("ALTER TABLE claim ADD COLUMN terms INTEGER");
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement st = db.createStatement();
                ResultSet rs = st.executeQuery("SELECT sheet_id, holiday_date FROM holiday")) {
            while (rs.next()) {
                holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                        .add(rs.getDate(2).toLocalDate());
            }
        }
        try (Statement st = db.createStatement();
                ResultSet rs = st.executeQuery("SELECT id, sheet_id, claim_date, due_by FROM claim");
                PreparedStatement update = db.prepareStatement("UPDATE claim SET terms = ? WHERE id = ?")) {
            while (rs.next()) {
                Set<LocalDate> days = holidays.getOrDefault(rs.getString(2), Set.of());
                LocalDate date = rs.getDate(3).toLocalDate();
                LocalDate dueBy = rs.getDate(4).toLocalDate();
                int terms = 0;
                if (dueBy.isAfter(date)) {
                    terms = 1;
                    for (LocalDate d = date.plusDays(1); d.isBefore(dueBy); d = d.plusDays(1)) {
                        if (BusinessDays.isBusinessDay(d, days)) {
                            terms++;
                        }
                    }
                }
                update.setInt(1, terms);
                update.setLong(2, rs.getLong(1));
                update.executeUpdate();
            }
        }
    }
}
