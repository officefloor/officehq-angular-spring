package net.officefloor.hq.app.history;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonGrade;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.setting.SettingValue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** Keeps each state a claim, a person, a rate, a person's grade or a dated setting is saved in, with the
 *  sheet's today when it was saved, so the sheet can be read as it stood at the end of a past day
 *  (see {@link History}). Written in the same transaction as the save itself. */
public class HistoryListener {

    private static final String CLAIM = "INSERT INTO claim_history (claim_id, sheet_id, person_id, amount,"
            + " claim_date, due_by, status, settled_due, stated, stated_due, corrects_number, deferred_until, claim_number,"
            + " opened_by, note, proofs, split_from, late_entry, complete, made_on)"
            + " SELECT c.id, c.sheet_id, c.person_id, c.amount, c.claim_date, c.due_by, c.status, c.settled_due,"
            + " c.stated, c.stated_due, c.corrects_number, c.deferred_until, c.claim_number, c.opened_by, c.note,"
            + " c.proofs, c.split_from, c.late_entry, TRUE, s.today"
            + " FROM claim c JOIN sheet s ON s.id = c.sheet_id WHERE c.id = ?";

    private static final String RATE = "INSERT INTO rate_history (sheet_id, grade, from_date, to_date, rate, made_on)"
            + " SELECT r.sheet_id, r.grade, r.from_date, r.to_date, r.rate, s.today"
            + " FROM rate r JOIN sheet s ON s.id = r.sheet_id WHERE r.id = ?";

    private static final String GRADE = "INSERT INTO person_grade_history (sheet_id, person_id, grade, from_date, made_on)"
            + " SELECT g.sheet_id, g.person_id, g.grade, g.from_date, s.today"
            + " FROM person_grade g JOIN sheet s ON s.id = g.sheet_id WHERE g.id = ?";

    private static final String SETTING = "INSERT INTO sheet_setting_value_history (sheet_id, setting_key, from_date,"
            + " setting_value, made_on) SELECT v.sheet_id, v.setting_key, v.from_date, v.setting_value, s.today"
            + " FROM sheet_setting_value v JOIN sheet s ON s.id = v.sheet_id WHERE v.id = ?";

    private static final String PERSON = "INSERT INTO person_history (person_id, sheet_id, name, terms, retired_from,"
            + " made_on) SELECT p.id, p.sheet_id, p.name, p.terms, p.retired_from, s.today"
            + " FROM person p JOIN sheet s ON s.id = p.sheet_id WHERE p.id = ?";

    private final ObjectProvider<JdbcTemplate> jdbc;

    public HistoryListener(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc;
    }

    @PostPersist
    @PostUpdate
    void saved(Object entity) {
        if (entity instanceof Claim claim) {
            jdbc.getObject().update(CLAIM, claim.getId());
        } else if (entity instanceof Person person) {
            jdbc.getObject().update(PERSON, person.getId());
        } else if (entity instanceof Rate rate) {
            jdbc.getObject().update(RATE, rate.getId());
        } else if (entity instanceof PersonGrade grade) {
            jdbc.getObject().update(GRADE, grade.getId());
        } else if (entity instanceof SettingValue value) {
            jdbc.getObject().update(SETTING, value.getId());
        }
    }
}
