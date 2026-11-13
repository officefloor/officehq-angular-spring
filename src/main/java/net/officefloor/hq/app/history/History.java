package net.officefloor.hq.app.history;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
