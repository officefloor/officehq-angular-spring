package db.migration;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.holiday.BusinessDays;
import net.officefloor.hq.app.sheet.SheetService;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Terms count business days: every due-by already on a sheet (of its claims and of their revisions in
 * the sheet's history) becomes the terms-th business day after the claim's date, by the terms it was
 * fixed with and the holidays on the sheet now. The terms a due-by was fixed with are the ones in
 * force on the claim's date (the person's own, their grade's or the sheet's) when the due-by fits
 * them, the holidays the due-by was moved past aside; otherwise the days from its date to its due-by.
 */
public class V54__business_day_terms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement st = db.createStatement();
                ResultSet rs = st.executeQuery("SELECT sheet_id, holiday_date FROM holiday")) {
            while (rs.next()) {
                holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                        .add(rs.getDate(2).toLocalDate());
            }
        }
        rework(db, holidays, "claim", "id");
        rework(db, holidays, "claim_revision", "id");
    }

    private void rework(Connection db, Map<String, Set<LocalDate>> holidays, String table, String key)
            throws SQLException {
        record Row(long id, String sheetId, long personId, LocalDate date, LocalDate dueBy) {
        }
        List<Row> rows = new ArrayList<>();
        try (Statement st = db.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT " + key + ", sheet_id, person_id, claim_date, due_by FROM " + table)) {
            while (rs.next()) {
                rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getDate(4).toLocalDate(),
                        rs.getDate(5).toLocalDate()));
            }
        }
        try (PreparedStatement update = db.prepareStatement("UPDATE " + table + " SET due_by = ? WHERE " + key + " = ?")) {
            for (Row row : rows) {
                Set<LocalDate> days = holidays.getOrDefault(row.sheetId(), Set.of());
                int terms = fixedTerms(db, row.sheetId(), row.personId(), row.date(), row.dueBy(), days);
                LocalDate dueBy = BusinessDays.after(row.date(), terms, days);
                if (!dueBy.equals(row.dueBy())) {
                    update.setDate(1, Date.valueOf(dueBy));
                    update.setLong(2, row.id());
                    update.executeUpdate();
                }
            }
        }
    }

    /** The terms a due-by was fixed with, counted in days. */
    private int fixedTerms(Connection db, String sheetId, long personId, LocalDate date, LocalDate dueBy,
            Set<LocalDate> holidays) throws SQLException {
        int days = (int) ChronoUnit.DAYS.between(date, dueBy);
        Integer inForce = termsInForce(db, sheetId, personId, date);
        if (inForce != null && inForce <= days) {
            boolean fits = true;
            for (LocalDate d = date.plusDays(inForce); d.isBefore(dueBy); d = d.plusDays(1)) {
                fits &= holidays.contains(d);
            }
            if (fits) {
                return inForce;
            }
        }
        return Math.max(days, 0);
    }

    /** The person's own terms, else their grade's in force on the day, else the sheet's. */
    private Integer termsInForce(Connection db, String sheetId, long personId, LocalDate day) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT terms FROM person WHERE id = ?")) {
            ps.setLong(1, personId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getObject(1) != null) {
                    return rs.getInt(1);
                }
            }
        }
        String grade = null;
        try (PreparedStatement ps = db.prepareStatement("SELECT grade FROM person_grade WHERE person_id = ?"
                + " AND (from_date IS NULL OR from_date <= ?) ORDER BY from_date DESC NULLS LAST LIMIT 1")) {
            ps.setLong(1, personId);
            ps.setDate(2, Date.valueOf(day));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    grade = rs.getString(1);
                }
            }
        }
        if (grade != null) {
            Integer graded = setting(db, sheetId, "terms-" + grade, day);
            if (graded != null) {
                return graded;
            }
        }
        Integer sheet = setting(db, sheetId, "terms", day);
        return sheet != null ? sheet : SheetService.STARTING_TERMS;
    }

    private Integer setting(Connection db, String sheetId, String key, LocalDate day) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT setting FROM setting_value WHERE sheet_id = ?"
                + " AND setting_key = ? AND from_date <= ? ORDER BY from_date DESC LIMIT 1")) {
            ps.setString(1, sheetId);
            ps.setString(2, key);
            ps.setDate(3, Date.valueOf(day));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBigDecimal(1) != null ? rs.getBigDecimal(1).intValue() : null;
            }
        }
    }
}
