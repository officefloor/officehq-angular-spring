package db.migration;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.holiday.HolidayService;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Terms count business days: every claim's due-by is worked out again as the terms-th business day after its
 *  date, skipping Saturdays, Sundays and the sheet's holidays as they are now. Each claim keeps the terms it was
 *  fixed with: its person's own terms, else the terms of their grade in force on its date, else the sheet's then
 *  (14 before any were set). */
public class V52__claim_due_by_business_days extends BaseJavaMigration {

    private static final int START_TERMS = 14;

    private record ClaimRow(long id, String sheetId, long personId, LocalDate date) {
    }

    private record Dated(LocalDate from, String value) {
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection db = context.getConnection();
        List<ClaimRow> claims = new ArrayList<>();
        try (PreparedStatement select = db.prepareStatement("SELECT id, sheet_id, person_id, claim_date FROM claim");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                claims.add(new ClaimRow(rows.getLong(1), rows.getString(2), rows.getLong(3),
                        rows.getDate(4).toLocalDate()));
            }
        }
        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (PreparedStatement select = db.prepareStatement("SELECT sheet_id, holiday_date FROM holiday");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                holidays.computeIfAbsent(rows.getString(1), k -> new HashSet<>()).add(rows.getDate(2).toLocalDate());
            }
        }
        Map<String, Map<String, List<Dated>>> settings = new HashMap<>();
        try (PreparedStatement select = db.prepareStatement("SELECT sheet_id, setting_key, from_date, setting_value"
                + " FROM sheet_setting WHERE setting_key LIKE 'terms%'"); ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                Date from = rows.getDate(3);
                settings.computeIfAbsent(rows.getString(1), k -> new HashMap<>())
                        .computeIfAbsent(rows.getString(2), k -> new ArrayList<>())
                        .add(new Dated(from == null ? null : from.toLocalDate(), rows.getString(4)));
            }
        }
        Map<Long, Integer> ownTerms = new HashMap<>();
        try (PreparedStatement select = db.prepareStatement("SELECT id, terms FROM person WHERE terms IS NOT NULL");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                ownTerms.put(rows.getLong(1), rows.getInt(2));
            }
        }
        Map<Long, List<Dated>> grades = new HashMap<>();
        try (PreparedStatement select = db.prepareStatement("SELECT person_id, from_date, grade FROM person_grade");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                Date from = rows.getDate(2);
                grades.computeIfAbsent(rows.getLong(1), k -> new ArrayList<>())
                        .add(new Dated(from == null ? null : from.toLocalDate(), rows.getString(3)));
            }
        }
        try (PreparedStatement update = db.prepareStatement("UPDATE claim SET due_by = ? WHERE id = ?")) {
            for (ClaimRow claim : claims) {
                Map<String, List<Dated>> sheetSettings = settings.getOrDefault(claim.sheetId(), Map.of());
                Integer terms = ownTerms.get(claim.personId());
                if (terms == null) {
                    String grade = gradeOn(grades.getOrDefault(claim.personId(), List.of()), claim.date());
                    String value = grade == null ? null
                            : valueOn(sheetSettings.getOrDefault("terms-" + grade, List.of()), claim.date());
                    if (value == null) {
                        value = valueOn(sheetSettings.getOrDefault("terms", List.of()), claim.date());
                    }
                    terms = value == null ? START_TERMS : Integer.parseInt(value);
                }
                LocalDate dueBy = HolidayService.businessDaysAfter(claim.date(), terms,
                        holidays.getOrDefault(claim.sheetId(), Set.of()));
                update.setDate(1, Date.valueOf(dueBy));
                update.setLong(2, claim.id());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    /** The value in force on a day: the one with the latest from-date on or before it (none: from the beginning). */
    private static String valueOn(List<Dated> records, LocalDate date) {
        Dated latest = null;
        for (Dated record : records) {
            if ((record.from() == null || !record.from().isAfter(date))
                    && (latest == null || (latest.from() != null && record.from() != null
                            && record.from().isAfter(latest.from())) || latest.from() == null && record.from() != null)) {
                latest = record;
            }
        }
        return latest == null ? null : latest.value();
    }

    /** The person's grade on a day: the one in force then, else their earliest. */
    private static String gradeOn(List<Dated> records, LocalDate date) {
        String grade = valueOn(records, date);
        if (grade != null || records.isEmpty()) {
            return grade;
        }
        Dated earliest = records.get(0);
        for (Dated record : records) {
            if (record.from() == null || (earliest.from() != null && record.from().isBefore(earliest.from()))) {
                earliest = record;
            }
        }
        return earliest.value();
    }
}
