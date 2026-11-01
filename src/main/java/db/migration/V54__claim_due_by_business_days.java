package db.migration;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Terms count business days: a claim's due-by is the terms-th day after its date that is not a
 * Saturday, a Sunday or a holiday on its sheet. Every existing claim's due-by is worked out again this
 * way, with the terms it was fixed with and the sheet's holidays now. The terms it was fixed with are
 * those in force on its date (its person's own, else its person's grade's then, else the sheet's) when
 * they agree with the due-by it shows (that day, or moved past holidays from it); otherwise the days from
 * its date to that due-by.
 */
public class V54__claim_due_by_business_days extends BaseJavaMigration {

    private static final int START_TERMS = 14;

    private record ClaimRow(long id, String sheetId, long personId, LocalDate date, LocalDate dueBy) {}

    private record PersonRow(String grade, Integer terms, TreeMap<LocalDate, String> datedGrades) {}

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT sheet_id, holiday_date FROM holiday")) {
            while (rows.next()) {
                holidays.computeIfAbsent(rows.getString(1), k -> new HashSet<>()).add(rows.getDate(2).toLocalDate());
            }
        }

        Map<String, Map<String, TreeMap<LocalDate, Integer>>> settings = new HashMap<>();
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT sheet_id, setting_key, from_date, setting_value"
                        + " FROM sheet_setting WHERE setting_key = 'terms' OR setting_key LIKE 'terms-%'")) {
            while (rows.next()) {
                String value = rows.getString(4);
                if (value == null || value.isBlank()) continue;
                settings.computeIfAbsent(rows.getString(1), k -> new HashMap<>())
                        .computeIfAbsent(rows.getString(2), k -> new TreeMap<>())
                        .put(rows.getDate(3).toLocalDate(), Integer.valueOf(value.strip()));
            }
        }

        Map<Long, PersonRow> people = new HashMap<>();
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT id, grade, terms FROM person")) {
            while (rows.next()) {
                int terms = rows.getInt(3);
                people.put(rows.getLong(1),
                        new PersonRow(rows.getString(2), rows.wasNull() ? null : terms, new TreeMap<>()));
            }
        }
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT person_id, from_date, grade FROM person_grade")) {
            while (rows.next()) {
                PersonRow person = people.get(rows.getLong(1));
                if (person != null) person.datedGrades().put(rows.getDate(2).toLocalDate(), rows.getString(3));
            }
        }

        List<ClaimRow> claims = new ArrayList<>();
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT id, sheet_id, person_id, claim_date, due_by FROM claim")) {
            while (rows.next()) {
                claims.add(new ClaimRow(rows.getLong(1), rows.getString(2), rows.getLong(3),
                        rows.getDate(4).toLocalDate(), rows.getDate(5).toLocalDate()));
            }
        }

        try (PreparedStatement update = connection.prepareStatement("UPDATE claim SET due_by = ? WHERE id = ?")) {
            for (ClaimRow claim : claims) {
                Set<LocalDate> sheetHolidays = holidays.getOrDefault(claim.sheetId(), Set.of());
                int terms = termsFixed(claim, people.get(claim.personId()),
                        settings.getOrDefault(claim.sheetId(), Map.of()), sheetHolidays);
                LocalDate dueBy = businessDaysAfter(claim.date(), terms, sheetHolidays);
                if (dueBy.equals(claim.dueBy())) continue;
                update.setDate(1, Date.valueOf(dueBy));
                update.setLong(2, claim.id());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    /** The terms the claim was fixed with, in days. */
    private static int termsFixed(ClaimRow claim, PersonRow person, Map<String, TreeMap<LocalDate, Integer>> settings,
            Set<LocalDate> holidays) {
        Integer terms = person == null ? null : person.terms();
        if (terms == null && person != null) {
            Map.Entry<LocalDate, String> dated = person.datedGrades().floorEntry(claim.date());
            String grade = dated == null ? person.grade() : dated.getValue();
            terms = valueOn(settings.get("terms-" + grade), claim.date());
        }
        if (terms == null) terms = valueOn(settings.get("terms"), claim.date());
        if (terms == null) terms = START_TERMS;
        LocalDate plain = claim.date().plusDays(terms);
        if (!plain.isAfter(claim.dueBy())) {
            LocalDate d = plain;
            while (d.isBefore(claim.dueBy()) && holidays.contains(d)) d = d.plusDays(1);
            if (d.equals(claim.dueBy())) return terms;
        }
        return (int) Math.max(0, ChronoUnit.DAYS.between(claim.date(), claim.dueBy()));
    }

    private static Integer valueOn(TreeMap<LocalDate, Integer> values, LocalDate date) {
        if (values == null) return null;
        Map.Entry<LocalDate, Integer> on = values.floorEntry(date);
        return on == null ? null : on.getValue();
    }

    private static LocalDate businessDaysAfter(LocalDate date, int terms, Set<LocalDate> holidays) {
        LocalDate d = date;
        for (int left = terms; left > 0; ) {
            d = d.plusDays(1);
            DayOfWeek day = d.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(d)) left--;
        }
        return d;
    }
}
