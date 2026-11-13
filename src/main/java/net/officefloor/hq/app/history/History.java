package net.officefloor.hq.app.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.PersonGrade;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.setting.SettingValue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The sheet as it stood at the end of a past day, read back from the states its claims, rates,
 *  grades, dated settings and policy date were saved in: every change made while the sheet's today
 *  was on or before that day counts, every later change does not. What is read back is a copy that
 *  is never saved. */
@Service
public class History {

    private final JdbcTemplate jdbc;

    public History(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The claim as it stood at the end of a day; empty when it had not been added by then. */
    public Optional<Claim> claim(Long claimId, LocalDate day) {
        return jdbc.query("SELECT * FROM claim_history WHERE claim_id = ? AND made_on <= ? ORDER BY id DESC LIMIT 1",
                (rs, n) -> Claim.asItWas(rs.getLong("claim_id"), rs.getString("sheet_id"), rs.getLong("person_id"),
                        rs.getBigDecimal("amount"), rs.getObject("claim_date", LocalDate.class),
                        rs.getObject("due_by", LocalDate.class), ClaimStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("settled_due"), rs.getBoolean("stated"), rs.getBigDecimal("stated_due"),
                        rs.getString("corrects_number"), rs.getObject("deferred_until", LocalDate.class)),
                claimId, day).stream().findFirst();
    }

    /** The sheet's claims as they stood at the end of a day, in the order given (the sheet's claims
     *  as they are now, newest first); a claim not yet added by then is left out. A state kept before
     *  the rest of a claim's state was kept takes the rest from the claim as it is now. */
    public List<Claim> claims(List<Claim> now, LocalDate day) {
        if (now.isEmpty()) {
            return List.of();
        }
        Map<Long, Claim> current = new LinkedHashMap<>();
        now.forEach(claim -> current.put(claim.getId(), claim));
        Map<Long, Claim> then = new LinkedHashMap<>();
        jdbc.query("SELECT h.* FROM claim_history h WHERE h.sheet_id = ? AND h.id = (SELECT MAX(l.id)"
                        + " FROM claim_history l WHERE l.claim_id = h.claim_id AND l.made_on <= ?)",
                (rs, n) -> {
                    Long id = rs.getLong("claim_id");
                    Claim was = Claim.asItWas(id, rs.getString("sheet_id"), rs.getLong("person_id"),
                            rs.getBigDecimal("amount"), rs.getObject("claim_date", LocalDate.class),
                            rs.getObject("due_by", LocalDate.class), ClaimStatus.valueOf(rs.getString("status")),
                            rs.getBigDecimal("settled_due"), rs.getBoolean("stated"), rs.getBigDecimal("stated_due"),
                            rs.getString("corrects_number"), rs.getObject("deferred_until", LocalDate.class));
                    Claim is = current.get(id);
                    if (rs.getBoolean("complete")) {
                        was.withRest(rs.getString("claim_number"), rs.getString("opened_by"), rs.getString("note"),
                                rs.getInt("proofs"), rs.getString("split_from"), rs.getBoolean("late_entry"));
                    } else if (is != null) {
                        was.withRest(is.getNumber(), is.getOpenedBy(), is.getNote(), is.getProofs(),
                                is.getSplitFrom(), is.isLateEntry());
                    }
                    return was;
                },
                now.get(0).getSheetId(), day).forEach(was -> then.put(was.getId(), was));
        return current.keySet().stream().filter(then::containsKey).map(then::get).toList();
    }

    /** A person as they stood at the end of a day: their name, own terms and retirement date. */
    public record PersonThen(String name, Integer terms, LocalDate retiredFrom) {
    }

    /** The sheet's people as they stood at the end of a day, by person id; a person saved only after
     *  it, or before their states were kept, is absent. */
    public Map<Long, PersonThen> people(String sheetId, LocalDate day) {
        Map<Long, PersonThen> then = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM person_history WHERE sheet_id = ? AND made_on <= ? ORDER BY id",
                (rs, n) -> Map.entry(rs.getLong("person_id"), new PersonThen(rs.getString("name"),
                        (Integer) rs.getObject("terms"), rs.getObject("retired_from", LocalDate.class))),
                sheetId, day).forEach(entry -> then.put(entry.getKey(), entry.getValue()));
        return then;
    }

    /** Whether a user watched each person at the end of a day, by person id, given who they watch
     *  now: the last start or stop kept by then; before the first one kept, the opposite of it; a
     *  person whose watching was never kept as it is now. */
    public Set<Long> watched(String sheetId, String user, Set<Long> now, LocalDate day) {
        Map<Long, Boolean> then = new LinkedHashMap<>();
        Set<Long> kept = new HashSet<>();
        jdbc.query("SELECT person_id, watched, made_on FROM person_watch_history WHERE sheet_id = ? AND user_name = ?"
                        + " ORDER BY id", (rs, n) -> {
                    Long personId = rs.getLong("person_id");
                    boolean watched = rs.getBoolean("watched");
                    if (!rs.getObject("made_on", LocalDate.class).isAfter(day)) {
                        then.put(personId, watched);
                    } else if (kept.add(personId) && !then.containsKey(personId)) {
                        then.put(personId, !watched);
                    }
                    kept.add(personId);
                    return null;
                }, sheetId, user);
        Set<Long> watched = new HashSet<>(now);
        then.forEach((personId, on) -> {
            if (on) {
                watched.add(personId);
            } else {
                watched.remove(personId);
            }
        });
        return watched;
    }

    /** Keep a user starting or stopping watching a person, with the sheet's today. */
    public void watchSet(String sheetId, Long personId, String user, boolean watched) {
        jdbc.update("INSERT INTO person_watch_history (sheet_id, person_id, user_name, watched, made_on)"
                + " SELECT id, ?, ?, ?, today FROM sheet WHERE id = ?", personId, user, watched, sheetId);
    }

    /** A period as it stood at the end of a day. */
    public record PeriodThen(Long id, String name, LocalDate from, LocalDate to, boolean closed, boolean softClosed,
            BigDecimal closedTotal, Map<Long, BigDecimal> personTotals) {
    }

    /** Keep a period's state as it is now saved, with the sheet's today. */
    public void periodSaved(Long periodId, String sheetId, String name, LocalDate from, LocalDate to, boolean closed,
            boolean softClosed, BigDecimal closedTotal, Map<Long, BigDecimal> personTotals) {
        String totals = String.join(",", personTotals.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().toPlainString()).toList());
        jdbc.update("INSERT INTO period_history (period_id, sheet_id, name, from_date, to_date, closed, soft_closed,"
                        + " closed_total, person_totals, made_on) SELECT ?, id, ?, ?, ?, ?, ?, ?, ?, today FROM sheet"
                        + " WHERE id = ?",
                periodId, name, from, to, closed, softClosed, closedTotal, totals, sheetId);
    }

    /** The sheet's periods as they stood at the end of a day, by period id; a period saved only after
     *  it, or before their states were kept, is absent. */
    public Map<Long, PeriodThen> periods(String sheetId, LocalDate day) {
        Map<Long, PeriodThen> then = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM period_history WHERE sheet_id = ? AND made_on <= ? ORDER BY id", (rs, n) -> {
            Map<Long, BigDecimal> totals = new LinkedHashMap<>();
            String kept = rs.getString("person_totals");
            if (!kept.isEmpty()) {
                for (String pair : kept.split(",")) {
                    String[] parts = pair.split("=");
                    totals.put(Long.valueOf(parts[0]), new BigDecimal(parts[1]));
                }
            }
            return new PeriodThen(rs.getLong("period_id"), rs.getString("name"),
                    rs.getObject("from_date", LocalDate.class), rs.getObject("to_date", LocalDate.class),
                    rs.getBoolean("closed"), rs.getBoolean("soft_closed"), rs.getBigDecimal("closed_total"), totals);
        }, sheetId, day).forEach(period -> then.put(period.id(), period));
        return then;
    }

    /** Keep a value set for a sheet setting that is not dated (null: blank), with the sheet's today. */
    public void settingSet(String sheetId, String key, String value) {
        jdbc.update("INSERT INTO sheet_setting_history (sheet_id, setting_key, setting_value, made_on)"
                + " SELECT id, ?, ?, today FROM sheet WHERE id = ?", key, value, sheetId);
    }

    /** The values of the sheet's settings that are not dated as they stood set at the end of a day, by
     *  key (a value null for blank); a key never set by then is absent. */
    public Map<String, String> settings(String sheetId, LocalDate day) {
        Map<String, String> then = new LinkedHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM sheet_setting_history WHERE sheet_id = ? AND made_on <= ?"
                        + " ORDER BY id",
                (rs, n) -> new String[] {rs.getString("setting_key"), rs.getString("setting_value")},
                sheetId, day).forEach(pair -> then.put(pair[0], pair[1]));
        return then;
    }

    /** The keys of the sheet's settings that are not dated whose values were ever kept. */
    public List<String> settingsKept(String sheetId) {
        return jdbc.queryForList("SELECT DISTINCT setting_key FROM sheet_setting_history WHERE sheet_id = ?",
                String.class, sheetId);
    }

    /** A grade's rates on the sheet as they stood at the end of a day: for each from-date, the rate
     *  last set from it by then. */
    public List<Rate> rates(String sheetId, String grade, LocalDate day) {
        Map<LocalDate, Rate> byFrom = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM rate_history WHERE sheet_id = ? AND grade = ? AND made_on <= ? ORDER BY id",
                (rs, n) -> new Rate(sheetId, grade, rs.getObject("from_date", LocalDate.class),
                        rs.getObject("to_date", LocalDate.class), rs.getBigDecimal("rate")),
                sheetId, grade, day).forEach(rate -> byFrom.put(rate.getFrom(), rate));
        return new ArrayList<>(byFrom.values());
    }

    /** A person's grades as they stood at the end of a day: for each from-date, the grade last given
     *  from it by then. */
    public List<PersonGrade> grades(Long personId, LocalDate day) {
        Map<LocalDate, PersonGrade> byFrom = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM person_grade_history WHERE person_id = ? AND made_on <= ? ORDER BY id",
                (rs, n) -> new PersonGrade(rs.getString("sheet_id"), personId, rs.getString("grade"),
                        rs.getObject("from_date", LocalDate.class)),
                personId, day).forEach(grade -> byFrom.put(grade.getFrom(), grade));
        return new ArrayList<>(byFrom.values());
    }

    /** The value of a dated setting in force on a date as the values stood at the end of a day: of
     *  the value last recorded from each from-date by then, the one with the latest from-date on or
     *  before the date; empty before every from-date. */
    public Optional<SettingValue> setting(String sheetId, String key, LocalDate on, LocalDate day) {
        Map<LocalDate, SettingValue> byFrom = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM sheet_setting_value_history WHERE sheet_id = ? AND setting_key = ? AND made_on <= ?"
                        + " ORDER BY id",
                (rs, n) -> new SettingValue(sheetId, key, rs.getObject("from_date", LocalDate.class),
                        rs.getBigDecimal("setting_value")),
                sheetId, key, day).forEach(value -> byFrom.put(value.getFrom(), value));
        return byFrom.values().stream().filter(value -> !value.getFrom().isAfter(on))
                .max((a, b) -> a.getFrom().compareTo(b.getFrom()));
    }

    /** The values recorded for the sheet's dated settings as they stood at the end of a day, by key,
     *  each list by from-date: for each from-date, the value last recorded from it by then. */
    public Map<String, List<SettingValue>> dated(String sheetId, LocalDate day) {
        Map<String, Map<LocalDate, SettingValue>> byKey = new TreeMap<>();
        jdbc.query("SELECT * FROM sheet_setting_value_history WHERE sheet_id = ? AND made_on <= ? ORDER BY id",
                (rs, n) -> new SettingValue(sheetId, rs.getString("setting_key"),
                        rs.getObject("from_date", LocalDate.class), rs.getBigDecimal("setting_value")),
                sheetId, day).forEach(value -> byKey.computeIfAbsent(value.getKey(), k -> new TreeMap<>())
                        .put(value.getFrom(), value));
        Map<String, List<SettingValue>> dated = new TreeMap<>();
        byKey.forEach((key, byFrom) -> dated.put(key, new ArrayList<>(byFrom.values())));
        return dated;
    }

    /** Keep the sheet's policy date as it is now set (null: blank), with the sheet's today. */
    public void policyDateSet(String sheetId) {
        jdbc.update("INSERT INTO sheet_policy_date_history (sheet_id, policy_date, made_on)"
                + " SELECT id, policy_date, today FROM sheet WHERE id = ?", sheetId);
    }

    /** The sheet's policy date as it stood set at the end of a day; null when it was blank. */
    public LocalDate policyDate(String sheetId, LocalDate day) {
        List<LocalDate> set = jdbc.query("SELECT policy_date FROM sheet_policy_date_history WHERE sheet_id = ?"
                        + " AND made_on <= ? ORDER BY id DESC LIMIT 1",
                (rs, n) -> rs.getObject("policy_date", LocalDate.class), sheetId, day);
        return set.isEmpty() ? null : set.get(0);
    }
}
