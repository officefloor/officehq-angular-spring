package net.officefloor.hq.app.sheet;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PatronRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.role.ActionNotAllowedException;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRoleRepository;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clones a sheet to another id never used: its today, settings, roles, rates, holidays, void reasons,
 * templates, people (with all they carry but their watches and statements), pools, crews, periods (all open) and
 * claims (statement bindings dropped, numbers given afresh by the clone's own numbering).
 */
@Service
public class SheetCloneService {

    private final JdbcTemplate jdbc;
    private final SheetRepository sheets;
    private final SheetService sheetService;
    private final RoleService roles;
    private final Rollup rollup;
    private final Audit audit;
    private final History history;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final PeriodRepository periods;
    private final PatronRepository patrons;
    private final SheetRoleRepository sheetRoles;

    public SheetCloneService(JdbcTemplate jdbc, SheetRepository sheets, SheetService sheetService, RoleService roles,
            Rollup rollup, Audit audit, History history, PersonRepository people, ClaimRepository claims,
            RateRepository rates, PeriodRepository periods, PatronRepository patrons, SheetRoleRepository sheetRoles) {
        this.jdbc = jdbc;
        this.sheets = sheets;
        this.sheetService = sheetService;
        this.roles = roles;
        this.rollup = rollup;
        this.audit = audit;
        this.history = history;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.periods = periods;
        this.patrons = patrons;
        this.sheetRoles = sheetRoles;
    }

    /** A steward clones the sheet to another id, which must never have been used. Writes SHEET_CLONE_MADE on
     *  this sheet, and SHEET_CLONED then a ROLLUP line of every person and the TOTALS on the clone. */
    @Transactional
    public Sheet cloneSheet(String id, String user, String to) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        if (!roles.isSteward(id, user)) {
            throw new ActionNotAllowedException("clone the sheet");
        }
        String target = to == null ? "" : to.trim();
        if (target.isEmpty()) {
            throw new CloneRefusedException("Enter the id of the sheet to clone to.");
        }
        if (sheets.existsById(target)) {
            throw new CloneRefusedException("The sheet to clone to must be one never used.");
        }
        copySheet(sheet, target, user);
        Map<Long, Long> personIds = copyPeople(id, target);
        copyPools(id, target, personIds);
        copyCrews(id, target, personIds);
        copyPeriods(id, target);
        List<long[]> claimIds = copyClaims(id, target, personIds);
        remapRates(target, claimIds);
        keepHistory(target);
        audit.recordOnCommit("SHEET_CLONE_MADE sheet=" + id + " by=" + user + " to=" + target);
        audit.recordOnCommit("SHEET_CLONED sheet=" + target + " by=" + user + " from=" + id);
        rollup.recordAll(target, user);
        return sheet;
    }

    private void copySheet(Sheet sheet, String target, String user) {
        String id = sheet.getId();
        jdbc.update("INSERT INTO sheet (id, created_by, created_at, today, last_claim_number, last_settle_batch,"
                + " policy_date, period_numbering_from, grace_days, archived, entry_date, pot, parent, alert_threshold,"
                + " escalate_after) SELECT ?, ?, ?, today, 0, last_settle_batch, policy_date, period_numbering_from,"
                + " grace_days, FALSE, entry_date, pot, parent, alert_threshold, escalate_after FROM sheet WHERE id = ?", target, user, Timestamp.from(Instant.now()), id);
        jdbc.update("INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)"
                + " SELECT ?, setting_key, from_date, setting_value FROM sheet_setting WHERE sheet_id = ?", target, id);
        jdbc.update("INSERT INTO sheet_permission (sheet_id, action, roles)"
                + " SELECT ?, action, roles FROM sheet_permission WHERE sheet_id = ?", target, id);
        jdbc.update("INSERT INTO sheet_role (sheet_id, user_name, role)"
                + " SELECT ?, user_name, role FROM sheet_role WHERE sheet_id = ? ORDER BY id", target, id);
        jdbc.update("INSERT INTO rate (sheet_id, grade, rate, from_date, to_date, earlier)"
                + " SELECT ?, grade, rate, from_date, to_date, earlier FROM rate WHERE sheet_id = ? ORDER BY id", target, id);
        jdbc.update("INSERT INTO holiday (sheet_id, holiday_date)"
                + " SELECT ?, holiday_date FROM holiday WHERE sheet_id = ? ORDER BY id", target, id);
        jdbc.update("INSERT INTO void_reason (sheet_id, reason)"
                + " SELECT ?, reason FROM void_reason WHERE sheet_id = ? ORDER BY id", target, id);
        jdbc.update("INSERT INTO claim_template (sheet_id, name, amount, note)"
                + " SELECT ?, name, amount, note FROM claim_template WHERE sheet_id = ? ORDER BY id", target, id);
        jdbc.update("INSERT INTO sheet_tag (sheet_id, tag)"
                + " SELECT ?, tag FROM sheet_tag WHERE sheet_id = ? ORDER BY id", target, id);
    }

    /** Copy the people with their grades, holds, retirements, aliases and patrons; the new id of each. */
    private Map<Long, Long> copyPeople(String id, String target) {
        Map<Long, Long> ids = new LinkedHashMap<>();
        for (Map<String, Object> p : jdbc.queryForList("SELECT * FROM person WHERE sheet_id = ? ORDER BY id", id)) {
            long newId = insert("INSERT INTO person (sheet_id, name, version, retired_from, terms, contact,"
                    + " list_position, origin_sheet, tags) VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?)", target, p.get("name"),
                    p.get("retired_from"), p.get("terms"), p.get("contact"), p.get("list_position"),
                    p.get("origin_sheet"), p.get("tags"));
            long oldId = ((Number) p.get("id")).longValue();
            ids.put(oldId, newId);
            jdbc.update("INSERT INTO person_grade (person_id, grade, from_date)"
                    + " SELECT ?, grade, from_date FROM person_grade WHERE person_id = ? ORDER BY id", newId, oldId);
            jdbc.update("INSERT INTO person_hold (person_id, from_date, to_date)"
                    + " SELECT ?, from_date, to_date FROM person_hold WHERE person_id = ? ORDER BY id", newId, oldId);
            jdbc.update("INSERT INTO person_retirement (person_id, from_date, to_date)"
                    + " SELECT ?, from_date, to_date FROM person_retirement WHERE person_id = ? ORDER BY id", newId,
                    oldId);
            jdbc.update("INSERT INTO person_alias (sheet_id, person_id, alias)"
                    + " SELECT ?, ?, alias FROM person_alias WHERE person_id = ? ORDER BY id", target, newId, oldId);
        }
        for (Map<String, Object> pp : jdbc.queryForList("SELECT * FROM person_patron WHERE sheet_id = ? ORDER BY id",
                id)) {
            jdbc.update("INSERT INTO person_patron (sheet_id, person_id, patron_id) VALUES (?, ?, ?)", target,
                    ids.get(((Number) pp.get("person_id")).longValue()),
                    ids.get(((Number) pp.get("patron_id")).longValue()));
        }
        return ids;
    }

    private void copyPools(String id, String target, Map<Long, Long> personIds) {
        Map<Long, Long> poolIds = new HashMap<>();
        for (Map<String, Object> p : jdbc.queryForList("SELECT * FROM pool WHERE sheet_id = ? ORDER BY id", id)) {
            poolIds.put(((Number) p.get("id")).longValue(),
                    insert("INSERT INTO pool (sheet_id, name) VALUES (?, ?)", target, p.get("name")));
        }
        for (Map<String, Object> m : jdbc.queryForList("SELECT m.* FROM pool_member m JOIN pool p ON p.id = m.pool_id"
                + " WHERE p.sheet_id = ? ORDER BY m.id", id)) {
            jdbc.update("INSERT INTO pool_member (pool_id, person_id, member_pool_id, share, from_date, to_date)"
                    + " VALUES (?, ?, ?, ?, ?, ?)", poolIds.get(((Number) m.get("pool_id")).longValue()),
                    mapped(personIds, m.get("person_id")), mapped(poolIds, m.get("member_pool_id")), m.get("share"),
                    m.get("from_date"), m.get("to_date"));
        }
    }

    private void copyCrews(String id, String target, Map<Long, Long> personIds) {
        Map<Long, Long> crewIds = new HashMap<>();
        for (Map<String, Object> c : jdbc.queryForList("SELECT * FROM crew WHERE sheet_id = ? ORDER BY id", id)) {
            crewIds.put(((Number) c.get("id")).longValue(),
                    insert("INSERT INTO crew (sheet_id, name, lead_person_id) VALUES (?, ?, ?)", target, c.get("name"),
                            mapped(personIds, c.get("lead_person_id"))));
        }
        for (Map<String, Object> m : jdbc.queryForList("SELECT m.* FROM crew_member m JOIN crew c ON c.id = m.crew_id"
                + " WHERE c.sheet_id = ? ORDER BY m.id", id)) {
            jdbc.update("INSERT INTO crew_member (crew_id, person_id) VALUES (?, ?)",
                    crewIds.get(((Number) m.get("crew_id")).longValue()), mapped(personIds, m.get("person_id")));
        }
    }

    /** Copy the periods, all open. */
    private void copyPeriods(String id, String target) {
        jdbc.update("INSERT INTO period (sheet_id, name, from_date, to_date, closed, soft_closed, claim_cap)"
                + " SELECT ?, name, from_date, to_date, FALSE, FALSE, claim_cap FROM period WHERE sheet_id = ? ORDER BY id",
                target, id);
    }

    /** A claim given a number, the sheet's today when it was opened and the order it was added. */
    private record Numbered(long newId, String oldNumber, LocalDate dueBy, LocalDate openedOn, long position,
            long oldId) {
    }

    /** Copy the claims with their status logs and comments, unbound by any statement, then number those that
     *  had a number afresh in the order they were opened. */
    private List<long[]> copyClaims(String id, String target, Map<Long, Long> personIds) {
        Map<Long, Long> positions = new HashMap<>();
        jdbc.query("SELECT id, list_position FROM person WHERE sheet_id = ?",
                rs -> {
                    positions.put(rs.getLong(1), rs.getLong(2));
                }, id);
        List<Numbered> numbered = new ArrayList<>();
        List<long[]> copied = new ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList("SELECT * FROM claim WHERE sheet_id = ? ORDER BY id", id)) {
            long oldId = ((Number) c.get("id")).longValue();
            long oldPerson = ((Number) c.get("person_id")).longValue();
            long newId = insert("INSERT INTO claim (sheet_id, person_id, list_order, amount, claim_date, due_by, terms,"
                    + " note, status, corrects_number, settled_due, opened_by, edited_by, stated, stated_due,"
                    + " deferred_until, proofs, split_from, settle_batch, late_entry, reminded_on, ref, void_reason,"
                    + " tags, escalated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    target, personIds.get(oldPerson), c.get("list_order"), c.get("amount"), c.get("claim_date"),
                    c.get("due_by"), c.get("terms"), c.get("note"), c.get("status"), c.get("corrects_number"),
                    c.get("settled_due"), c.get("opened_by"), c.get("edited_by"), c.get("deferred_until"),
                    c.get("proofs"), c.get("split_from"), c.get("settle_batch"), c.get("late_entry"),
                    c.get("reminded_on"), c.get("ref"), c.get("void_reason"), c.get("tags"),
                    c.get("escalated"));
            copied.add(new long[] { oldId, newId });
            jdbc.update("INSERT INTO claim_status_log (claim_id, seq, status, by_user, on_date, due)"
                    + " SELECT ?, seq, status, by_user, on_date, due FROM claim_status_log WHERE claim_id = ?",
                    newId, oldId);
            jdbc.update("INSERT INTO claim_comment (claim_id, seq, by_user, on_date, comment_text)"
                    + " SELECT ?, seq, by_user, on_date, comment_text FROM claim_comment WHERE claim_id = ?",
                    newId, oldId);
            String number = (String) c.get("claim_number");
            if (number != null) {
                LocalDate openedOn = jdbc.queryForList("SELECT on_date FROM claim_status_log WHERE claim_id = ?"
                        + " AND status = 'OPEN' ORDER BY seq", Date.class, oldId).stream().findFirst()
                        .map(Date::toLocalDate).orElse(((Date) c.get("claim_date")).toLocalDate());
                numbered.add(new Numbered(newId, number, ((Date) c.get("due_by")).toLocalDate(), openedOn,
                        positions.getOrDefault(oldPerson, 0L), oldId));
            }
        }
        numbered.sort(Comparator.comparing(Numbered::openedOn).thenComparingLong(Numbered::position)
                .thenComparingLong(Numbered::oldId));
        Map<String, String> numbers = new HashMap<>();
        for (Numbered n : numbered) {
            String number = sheetService.nextClaimNumber(target, n.dueBy());
            numbers.put(n.oldNumber(), number);
            jdbc.update("UPDATE claim SET claim_number = ? WHERE id = ?", number, n.newId());
        }
        // the numbers a claim names (the claim it corrects, the claim it was split from) follow the new ones
        for (long[] ids : copied) {
            jdbc.update("UPDATE claim SET corrects_number = COALESCE(?, corrects_number),"
                    + " split_from = COALESCE(?, split_from) WHERE id = ?",
                    numbers.get(jdbc.queryForObject("SELECT corrects_number FROM claim WHERE id = ?", String.class, ids[1])),
                    numbers.get(jdbc.queryForObject("SELECT split_from FROM claim WHERE id = ?", String.class, ids[1])),
                    ids[1]);
        }
        return copied;
    }

    /** A forward-only rate of the clone keeps applying only to the claims that came after it: each claim id its
     *  earlier entries name becomes the new id of the last claim copied with an id at most that (copied in id
     *  order), or 0 when none was. */
    private void remapRates(String target, List<long[]> claimIds) {
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, earlier FROM rate WHERE sheet_id = ?"
                + " AND earlier IS NOT NULL", target)) {
            String earlier = Rate.remapEarlier((String) r.get("earlier"), upTo -> {
                long mapped = 0;
                for (long[] ids : claimIds) {
                    if (ids[0] <= upTo) {
                        mapped = ids[1];
                    }
                }
                return mapped;
            });
            jdbc.update("UPDATE rate SET earlier = ? WHERE id = ?", earlier, r.get("id"));
        }
    }

    /** Keep the first version of each row of the clone the as-of view reads. */
    private void keepHistory(String target) {
        sheets.findById(target).ifPresent(history::keep);
        people.findBySheetIdOrderByPositionAscIdAsc(target).forEach(history::saved);
        claims.findBySheetIdOrderByIdDesc(target).forEach(history::saved);
        rates.findBySheetIdOrderByGradeAsc(target).forEach(history::saved);
        periods.findBySheetIdOrderByFromDateAsc(target).forEach(history::saved);
        patrons.findBySheetIdOrderByIdAsc(target).forEach(history::saved);
        sheetRoles.findBySheetIdOrderByUserNameAsc(target).forEach(history::saved);
    }

    private static Long mapped(Map<Long, Long> ids, Object old) {
        return old == null ? null : ids.get(((Number) old).longValue());
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[] { "ID" });
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}
