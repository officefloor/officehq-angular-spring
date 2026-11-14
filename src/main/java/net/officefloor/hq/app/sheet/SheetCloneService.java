package net.officefloor.hq.app.sheet;

import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Clones a sheet to another id never used: the clone starts as a copy of the sheet as it is now. */
@Service
public class SheetCloneService {

    /** A claim of the sheet as copied to the clone: its id there, its number here, the sheet's today
     *  when it was opened here (null: never), its due-by, and the numbers it corrects and was split
     *  from here. */
    private record Copied(Long id, String number, LocalDate openedOn, LocalDate dueBy, String corrects,
            String splitFrom) {
    }

    private final SheetService sheets;
    private final SettingService settings;
    private final ClaimService claims;
    private final RollupService rollups;
    private final History history;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final Audit audit;

    public SheetCloneService(SheetService sheets, SettingService settings, ClaimService claims,
            RollupService rollups, History history, JdbcTemplate jdbc, EntityManager entities, Audit audit) {
        this.sheets = sheets;
        this.settings = settings;
        this.claims = claims;
        this.rollups = rollups;
        this.history = history;
        this.jdbc = jdbc;
        this.entities = entities;
        this.audit = audit;
    }

    /** A steward clones the sheet to another id, which must never have been used. The clone takes the
     *  sheet's today, settings, roles, rates, holidays, void reasons and templates; its people with
     *  their grades, terms, contacts, aliases, holds, retirements and patrons; its pools; its crews; its periods,
     *  all open; and its claims with their status, frozen figures, dates, notes, tags, comments and
     *  logs. Each claim that had a number takes the clone's next number, in the order the claims were
     *  opened (by the sheet's today when each was, then the order they were added). Statements, and
     *  the figures they bound, are not copied. The sheet writes its line; the clone writes its own,
     *  then every person's rollup line and its totals. */
    @Transactional
    public void cloneTo(String sheetId, String user, String toSheetId) {
        String to = toSheetId == null ? "" : toSheetId.trim();
        if (to.isEmpty() || to.length() > 200) {
            throw new CloneRefusedException("A sheet id to clone to is needed");
        }
        sheets.lock(sheetId);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new CloneRefusedException(user + " is not a steward: only a steward clones the sheet");
        }
        if (to.equals(sheetId) || sheets.exists(to)) {
            throw new CloneRefusedException(to + " has been used already: a sheet is cloned to a new one only");
        }
        entities.flush();

        copy(sheetId, to);
        entities.clear();
        history.sheetKept(to);

        audit.recordOnCommit("SHEET_CLONE_MADE sheet=" + sheetId + " by=" + user + " to=" + to);
        audit.recordOnCommit("SHEET_CLONED sheet=" + to + " by=" + user + " from=" + sheetId);
        rollups.recordAll(to, user);
    }

    private void copy(String from, String to) {
        jdbc.update("INSERT INTO sheet (id, today, last_claim_number, last_batch_number, policy_date,"
                + " period_numbering_from, grace_days, entry_date, pot, parent_id, archived, fiscal_start_month,"
                + " alert_threshold, escalate_after, review_sla, proof_kinds)"
                + " SELECT ?, today, 0, 0, policy_date, period_numbering_from, grace_days, entry_date, pot, parent_id,"
                + " FALSE, fiscal_start_month, alert_threshold, escalate_after, review_sla, proof_kinds FROM sheet WHERE id = ?", to, from);
        LocalDate today = jdbc.queryForObject("SELECT today FROM sheet WHERE id = ?", LocalDate.class, to);

        // The sheet's own lists: settings, roles, rates, holidays, void reasons, templates and periods.
        copyAll("sheet_setting_value", "setting_key, from_date, setting_value, setting_text", from, to);
        copyAll("sheet_permission", "action, roles", from, to);
        copyAll("sheet_role", "user_name, role", from, to);
        copyAll("sheet_tag", "tag", from, to);
        copyAll("rate", "grade, rate, from_date, to_date", from, to);
        copyAll("holiday", "holiday_date", from, to);
        copyAll("void_reason", "reason", from, to);
        copyAll("claim_template", "name, amount, note", from, to);
        copyAll("period", "name, from_date, to_date", from, to);
        jdbc.update("INSERT INTO sheet_setting_history (sheet_id, setting_key, setting_value, made_on)"
                + " SELECT ?, setting_key, setting_value, ? FROM sheet_setting_history WHERE sheet_id = ? ORDER BY id",
                to, today, from);

        // People, in list order, then what each one has.
        Map<Long, Long> people = new LinkedHashMap<>();
        for (Long id : ids("SELECT id FROM person WHERE sheet_id = ? ORDER BY list_position, id", from)) {
            people.put(id, insert("INSERT INTO person (sheet_id, name, retired_from, terms, added_on, contact, tags,"
                    + " origin_sheet_id) SELECT ?, name, retired_from, terms, added_on, contact, tags, origin_sheet_id"
                    + " FROM person WHERE id = ?", to, id));
        }
        for (Map.Entry<Long, Long> person : people.entrySet()) {
            Long was = person.getKey();
            Long is = person.getValue();
            Long mergedInto = jdbc.queryForObject("SELECT merged_into FROM person WHERE id = ?", Long.class, was);
            if (mergedInto != null) {
                jdbc.update("UPDATE person SET merged_into = ? WHERE id = ?", people.get(mergedInto), is);
            }
            copyOf(was, is, to, "person_grade", "grade, from_date");
            copyOf(was, is, to, "person_terms", "terms, from_date");
            copyOf(was, is, to, "person_alias", "alias, alias_key, made_on");
            copyOf(was, is, to, "person_hold", "from_date, to_date, made_on");
            copyOf(was, is, to, "person_retirement", "from_date, to_date");
            for (Map<String, Object> patron : jdbc.queryForList(
                    "SELECT patron_id, made_on FROM person_patron WHERE person_id = ? ORDER BY id", was)) {
                jdbc.update("INSERT INTO person_patron (sheet_id, person_id, patron_id, made_on) VALUES (?, ?, ?, ?)",
                        to, is, people.get(((Number) patron.get("PATRON_ID")).longValue()), patron.get("MADE_ON"));
            }
        }

        // Pools, then their members: people and pools of the clone.
        Map<Long, Long> pools = new HashMap<>();
        for (Long id : ids("SELECT id FROM pool WHERE sheet_id = ? ORDER BY id", from)) {
            pools.put(id, insert("INSERT INTO pool (sheet_id, name, made_on) SELECT ?, name, made_on FROM pool WHERE id = ?", to, id));
        }
        for (Map<String, Object> member : jdbc.queryForList("SELECT m.* FROM pool_member m JOIN pool p"
                + " ON p.id = m.pool_id WHERE p.sheet_id = ? ORDER BY m.id", from)) {
            jdbc.update("INSERT INTO pool_member (pool_id, person_id, member_pool_id, share, from_date, to_date,"
                    + " made_on) VALUES (?, ?, ?, ?, ?, ?, ?)", pools.get(idOf(member.get("POOL_ID"))),
                    people.get(idOf(member.get("PERSON_ID"))), pools.get(idOf(member.get("MEMBER_POOL_ID"))),
                    member.get("SHARE"), member.get("FROM_DATE"), member.get("TO_DATE"), member.get("MADE_ON"));
        }

        // Crews, then their members: people of the clone.
        Map<Long, Long> crews = new HashMap<>();
        for (Map<String, Object> crew : jdbc.queryForList("SELECT id, lead_id FROM crew WHERE sheet_id = ? ORDER BY id",
                from)) {
            // A crew whose lead has left the sheet is not carried.
            if (!people.containsKey(idOf(crew.get("LEAD_ID")))) {
                continue;
            }
            crews.put(idOf(crew.get("ID")), insert("INSERT INTO crew (sheet_id, name, lead_id, made_on)"
                    + " SELECT ?, name, ?, made_on FROM crew WHERE id = ?", to, people.get(idOf(crew.get("LEAD_ID"))), idOf(crew.get("ID"))));
        }
        for (Map<String, Object> member : jdbc.queryForList("SELECT m.* FROM crew_member m JOIN crew c"
                + " ON c.id = m.crew_id WHERE c.sheet_id = ? ORDER BY m.id", from)) {
            if (!crews.containsKey(idOf(member.get("CREW_ID")))) {
                continue;
            }
            jdbc.update("INSERT INTO crew_member (crew_id, person_id, made_on) VALUES (?, ?, ?)",
                    crews.get(idOf(member.get("CREW_ID"))), people.get(idOf(member.get("PERSON_ID"))),
                    member.get("MADE_ON"));
        }

        // Claims, person by person in the order they were listed, unnumbered, unbound and unlocked;
        // then their logs, comments and lines.
        List<Copied> copied = new ArrayList<>();
        for (Map.Entry<Long, Long> person : people.entrySet()) {
            for (Long id : ids("SELECT id FROM claim WHERE person_id = ? ORDER BY listed, id", person.getKey())) {
                Long is = insert("INSERT INTO claim (sheet_id, person_id, amount, claim_date, note, status,"
                        + " settled_due, opened_by, stated, corrects_number, due_by, deferred_until, proofs, split_from,"
                        + " late_entry, reminded_on, ref, ref_key, tags, void_reason, edited_by, escalated, waived,"
                        + " proofs_receipt, proofs_photo, held)"
                        + " SELECT ?, ?, amount, claim_date, note, status, settled_due, opened_by, FALSE, corrects_number,"
                        + " due_by, deferred_until, proofs, split_from, late_entry, reminded_on, ref, ref_key, tags,"
                        + " void_reason, edited_by, escalated, waived, proofs_receipt, proofs_photo, held FROM claim WHERE id = ?", to, person.getValue(), id);
                jdbc.update("INSERT INTO claim_status_log (sheet_id, claim_id, status, changed_by, changed_on, due)"
                        + " SELECT ?, ?, status, changed_by, changed_on, due FROM claim_status_log WHERE claim_id = ?"
                        + " ORDER BY id", to, is, id);
                jdbc.update("INSERT INTO claim_comment (sheet_id, claim_id, written_by, written_on, text)"
                        + " SELECT ?, ?, written_by, written_on, text FROM claim_comment WHERE claim_id = ? ORDER BY id",
                        to, is, id);
                jdbc.update("INSERT INTO claim_line (claim_id, label, qty, unit_amount)"
                        + " SELECT ?, label, qty, unit_amount FROM claim_line WHERE claim_id = ? ORDER BY id", is, id);
                Map<String, Object> was = jdbc.queryForMap("SELECT claim_number, due_by, corrects_number, split_from"
                        + " FROM claim WHERE id = ?", id);
                LocalDate openedOn = jdbc.query("SELECT changed_on FROM claim_status_log WHERE claim_id = ?"
                        + " AND status = 'OPEN' ORDER BY id LIMIT 1", (rs, n) -> rs.getObject(1, LocalDate.class), id)
                        .stream().findFirst().orElse(null);
                copied.add(new Copied(is, (String) was.get("CLAIM_NUMBER"), openedOn,
                        ((java.sql.Date) was.get("DUE_BY")).toLocalDate(), (String) was.get("CORRECTS_NUMBER"),
                        (String) was.get("SPLIT_FROM")));
            }
        }

        // Numbers given afresh by the clone's own numbering, in the order the claims were opened.
        Map<String, String> numbers = new HashMap<>();
        copied.stream().filter(claim -> claim.number() != null)
                .sorted(Comparator.comparing((Copied claim) -> claim.openedOn() != null ? claim.openedOn() : LocalDate.MAX)
                        .thenComparing(Copied::id))
                .forEach(claim -> {
                    String number = claims.numberFor(to, claim.dueBy());
                    numbers.put(claim.number(), number);
                    jdbc.update("UPDATE claim SET claim_number = ? WHERE id = ?", number, claim.id());
                });
        for (Copied claim : copied) {
            if (numbers.containsKey(claim.corrects()) || numbers.containsKey(claim.splitFrom())) {
                jdbc.update("UPDATE claim SET corrects_number = ?, split_from = ? WHERE id = ?",
                        numbers.getOrDefault(claim.corrects(), claim.corrects()),
                        numbers.getOrDefault(claim.splitFrom(), claim.splitFrom()), claim.id());
            }
        }
    }

    /** Copy every row a table holds for the sheet to the clone. */
    private void copyAll(String table, String columns, String from, String to) {
        jdbc.update("INSERT INTO " + table + " (sheet_id, " + columns + ") SELECT ?, " + columns + " FROM " + table
                + " WHERE sheet_id = ?" + (table.equals("sheet_tag") ? "" : " ORDER BY id"), to, from);
    }

    /** Copy every row a table holds for a person to the person's copy on the clone. */
    private void copyOf(Long was, Long is, String to, String table, String columns) {
        jdbc.update("INSERT INTO " + table + " (sheet_id, person_id, " + columns + ") SELECT ?, ?, " + columns
                + " FROM " + table + " WHERE person_id = ? ORDER BY id", to, is, was);
    }

    private List<Long> ids(String sql, Object arg) {
        return jdbc.query(sql, (rs, n) -> rs.getLong(1), arg);
    }

    private static Long idOf(Object id) {
        return id == null ? null : ((Number) id).longValue();
    }

    /** Insert one row, giving back the id it was given. */
    private Long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[] {"ID"});
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
