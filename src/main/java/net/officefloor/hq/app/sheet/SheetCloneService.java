package net.officefloor.hq.app.sheet;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.SettingsService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Clones a sheet to another id, never used before. */
@Service
public class SheetCloneService {

    private final JdbcTemplate jdbc;
    private final SheetRepository sheets;
    private final SheetService sheetService;
    private final SettingsService settings;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RollupService rollup;
    private final Audit audit;

    public SheetCloneService(DataSource dataSource, SheetRepository sheets, SheetService sheetService,
            SettingsService settings, ClaimRepository claims, ClaimService claimService, RollupService rollup,
            Audit audit) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.sheets = sheets;
        this.sheetService = sheetService;
        this.settings = settings;
        this.claims = claims;
        this.claimService = claimService;
        this.rollup = rollup;
        this.audit = audit;
    }

    /**
     * A steward clones the sheet to the other id. The clone gets the sheet's today, settings, roles, rates (and their
     * history), holidays, void reasons, templates and claim tags; its people with their grades, terms, contacts,
     * aliases, holds, retirements and patrons, in list order; its pools and their members; its periods, all open; and
     * its claims with status, frozen figures, dates, notes, tags, references, comments and logs, listed as they were.
     * Each claim that had a number takes the clone's next, in the order the claims were opened (by the sheet's today
     * when each was opened, then the order they were added to the clone: by person in list order, then as they were
     * listed). Statements, the figures they bound, watches, delegations and settle batches are not copied. Refused
     * unless the user is a steward, and unless the other id was never used.
     */
    @Transactional
    public void cloneSheet(String by, String sheetId, String toSheetId) {
        if (!settings.isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can clone the sheet.");
        }
        sheetService.lock(sheetId);
        if (sheetId.equals(toSheetId) || sheets.existsById(toSheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The other sheet must be one never used.");
        }
        claims.flush();
        try {
            jdbc.update("INSERT INTO sheet (id, created_at, today, policy_date, period_numbering_from, entry_date,"
                    + " grace_days, pot, alert_threshold, parent_id, fiscal_start_month, escalate_after) SELECT ?,"
                    + " CURRENT_TIMESTAMP, today, policy_date, period_numbering_from, entry_date, grace_days, pot,"
                    + " alert_threshold, parent_id, fiscal_start_month, escalate_after FROM sheet"
                    + " WHERE id = ?",
                    toSheetId, sheetId);
        } catch (DataIntegrityViolationException e) {
            // another user started it at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The other sheet must be one never used.");
        }
        copySettings(sheetId, toSheetId);
        Map<Long, Long> people = copyPeople(sheetId, toSheetId);
        copyPools(sheetId, toSheetId, people);
        jdbc.update("INSERT INTO period (sheet_id, name, from_date, to_date) SELECT ?, name, from_date, to_date"
                + " FROM period WHERE sheet_id = ? ORDER BY id", toSheetId, sheetId);
        copyClaims(sheetId, toSheetId, people);

        audit.recordOnCommit("SHEET_CLONE_MADE sheet=" + sheetId + " by=" + by + " to=" + toSheetId);
        audit.recordOnCommit("SHEET_CLONED sheet=" + toSheetId + " by=" + by + " from=" + sheetId);
        rollup.recordAll(by, toSheetId);
    }

    /** The sheet's settings, permissions, roles, rates, holidays, void reasons, templates and claim tags. */
    private void copySettings(String sheetId, String toSheetId) {
        jdbc.update("INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)"
                + " SELECT ?, setting_key, from_date, setting_value FROM sheet_setting WHERE sheet_id = ?",
                toSheetId, sheetId);
        jdbc.update("INSERT INTO sheet_setting_change (sheet_id, pos, setting_key, from_date, setting_value, made_on)"
                + " SELECT ?, pos, setting_key, from_date, setting_value, made_on FROM sheet_setting_change"
                + " WHERE sheet_id = ?", toSheetId, sheetId);
        jdbc.update("INSERT INTO sheet_permission (sheet_id, action_name, roles)"
                + " SELECT ?, action_name, roles FROM sheet_permission WHERE sheet_id = ?", toSheetId, sheetId);
        jdbc.update("INSERT INTO sheet_role (sheet_id, user_name, role) SELECT ?, user_name, role FROM sheet_role"
                + " WHERE sheet_id = ? ORDER BY id", toSheetId, sheetId);
        jdbc.update("INSERT INTO rate (sheet_id, grade, pct, from_date, to_date, after_mark) SELECT ?, grade, pct,"
                + " from_date, to_date, after_mark FROM rate WHERE sheet_id = ? ORDER BY id", toSheetId, sheetId);
        jdbc.update("INSERT INTO rate_change (sheet_id, grade, from_date, to_date, pct, made_on, after_mark)"
                + " SELECT ?, grade, from_date, to_date, pct, made_on, after_mark FROM rate_change WHERE sheet_id = ? ORDER BY id",
                toSheetId, sheetId);
        jdbc.update("INSERT INTO holiday (sheet_id, on_date) SELECT ?, on_date FROM holiday WHERE sheet_id = ?"
                + " ORDER BY id", toSheetId, sheetId);
        jdbc.update("INSERT INTO void_reason (sheet_id, reason) SELECT ?, reason FROM void_reason WHERE sheet_id = ?"
                + " ORDER BY id", toSheetId, sheetId);
        jdbc.update("INSERT INTO claim_template (sheet_id, name, amount, note) SELECT ?, name, amount, note"
                + " FROM claim_template WHERE sheet_id = ? ORDER BY id", toSheetId, sheetId);
        jdbc.update("INSERT INTO claim_tag (sheet_id, tag) SELECT ?, tag FROM claim_tag WHERE sheet_id = ?"
                + " ORDER BY id", toSheetId, sheetId);
    }

    /** The sheet's people, in list order, with what is theirs; the clone's id of each by the sheet's. */
    private Map<Long, Long> copyPeople(String sheetId, String toSheetId) {
        Map<Long, Long> ids = new LinkedHashMap<>();
        List<Long> people = jdbc.queryForList("SELECT id FROM person WHERE sheet_id = ? ORDER BY list_position, id",
                Long.class, sheetId);
        for (Long id : people) {
            Long copy = insert("INSERT INTO person (sheet_id, name, retired_from, terms, added_on, retired_on, contact,"
                    + " list_position, origin_sheet, tags) SELECT ?, name, retired_from, terms, added_on, retired_on,"
                    + " contact, list_position, origin_sheet, tags FROM person WHERE id = ?", toSheetId, id);
            ids.put(id, copy);
            jdbc.update("INSERT INTO person_grade (person_id, grade, from_date, made_on) SELECT ?, grade, from_date,"
                    + " made_on FROM person_grade WHERE person_id = ?", copy, id);
            jdbc.update("INSERT INTO person_hold (person_id, from_date, to_date, made_on) SELECT ?, from_date,"
                    + " to_date, made_on FROM person_hold WHERE person_id = ?", copy, id);
            jdbc.update("INSERT INTO person_retirement (person_id, from_date, to_date, retired_on, made_on)"
                    + " SELECT ?, from_date, to_date, retired_on, made_on FROM person_retirement WHERE person_id = ?",
                    copy, id);
            jdbc.update("INSERT INTO person_alias (person_id, sheet_id, alias) SELECT ?, ?, alias FROM person_alias"
                    + " WHERE person_id = ?", copy, toSheetId, id);
        }
        for (Map<String, Object> p : jdbc.queryForList("SELECT person_id, patron_id FROM person_patron"
                + " WHERE sheet_id = ? ORDER BY id", sheetId)) {
            jdbc.update("INSERT INTO person_patron (sheet_id, person_id, patron_id) VALUES (?, ?, ?)", toSheetId,
                    ids.get(id(p.get("PERSON_ID"))), ids.get(id(p.get("PATRON_ID"))));
        }
        return ids;
    }

    /** The sheet's pools and their members, people and pools alike. */
    private void copyPools(String sheetId, String toSheetId, Map<Long, Long> people) {
        Map<Long, Long> pools = new HashMap<>();
        for (Long id : jdbc.queryForList("SELECT id FROM pool WHERE sheet_id = ? ORDER BY id", Long.class, sheetId)) {
            pools.put(id, insert("INSERT INTO pool (sheet_id, name) SELECT ?, name FROM pool WHERE id = ?", toSheetId,
                    id));
        }
        for (Map<String, Object> m : jdbc.queryForList("SELECT pool_id, person_id, member_pool_id, share, from_date,"
                + " to_date FROM pool_member WHERE sheet_id = ? ORDER BY id", sheetId)) {
            jdbc.update("INSERT INTO pool_member (sheet_id, pool_id, person_id, member_pool_id, share, from_date,"
                    + " to_date) VALUES (?, ?, ?, ?, ?, ?, ?)", toSheetId, pools.get(id(m.get("POOL_ID"))),
                    people.get(id(m.get("PERSON_ID"))), pools.get(id(m.get("MEMBER_POOL_ID"))), m.get("SHARE"),
                    m.get("FROM_DATE"), m.get("TO_DATE"));
        }
    }

    /**
     * The sheet's claims, by person in list order and as listed, with their logs, comments and lines; then numbered afresh
     * and listed as they were.
     */
    private void copyClaims(String sheetId, String toSheetId, Map<Long, Long> people) {
        List<Claim> originals = new ArrayList<>(claims.findBySheetIdOrderByListedDescIdDesc(sheetId));
        originals.sort(Comparator.comparing(Claim::getListed).thenComparing(Claim::getId));
        Map<Long, Long> copies = new LinkedHashMap<>();
        for (Long person : people.keySet()) {
            for (Claim c : originals) {
                if (!c.getPersonId().equals(person)) {
                    continue;
                }
                Long copy = insert("INSERT INTO claim (sheet_id, person_id, amount, claim_date, note, status,"
                        + " settled_due, opened_by, edited_by, corrects, split_from, rounds_on, due_by, terms,"
                        + " deferred_until, deferred_on, reminded_on, late_entry, proofs, ref, tags, void_reason,"
                        + " added_mark, escalated) SELECT ?, ?, amount, claim_date, note, status, settled_due, opened_by,"
                        + " edited_by, corrects, split_from, rounds_on, due_by, terms, deferred_until, deferred_on,"
                        + " reminded_on, late_entry, proofs, ref, tags, void_reason, added_mark, escalated FROM claim"
                        + " WHERE id = ?", toSheetId,
                        people.get(person), c.getId());
                copies.put(c.getId(), copy);
                jdbc.update("INSERT INTO claim_log (claim_id, status, by_user, on_date, due) SELECT ?, status,"
                        + " by_user, on_date, due FROM claim_log WHERE claim_id = ? ORDER BY id", copy, c.getId());
                jdbc.update("INSERT INTO claim_comment (claim_id, by_user, on_date, text) SELECT ?, by_user,"
                        + " on_date, text FROM claim_comment WHERE claim_id = ? ORDER BY id", copy, c.getId());
                jdbc.update("INSERT INTO claim_line (claim_id, label, qty, unit) SELECT ?, label, qty, unit"
                        + " FROM claim_line WHERE claim_id = ? ORDER BY id", copy, c.getId());
            }
        }

        Map<Long, Claim> numbered = new LinkedHashMap<>();
        for (Claim c : originals) {
            if (c.getNumber() != null) {
                numbered.put(copies.get(c.getId()), c);
            }
        }
        List<Claim> cloned = new ArrayList<>(claims.findAllById(numbered.keySet()));
        // numbered afresh in the order they were opened, then added
        cloned.sort(Comparator.comparing(Claim::openedOn, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Claim::getId));
        Map<String, String> renumbered = new HashMap<>();
        for (Claim c : cloned) {
            claimService.numberCopy(toSheetId, c);
            claims.saveAndFlush(c);
            renumbered.put(numbered.get(c.getId()).getNumber(), c.getNumber());
        }
        // a claim naming another by number names it by its new one
        for (Claim c : claims.findAllById(copies.values())) {
            if (c.getCorrects() != null || c.getSplitFrom() != null) {
                c.setCorrects(c.getCorrects() == null ? null : renumbered.get(c.getCorrects()));
                c.setSplitFrom(c.getSplitFrom() == null ? null : renumbered.get(c.getSplitFrom()));
                claims.saveAndFlush(c);
            }
        }
        // listed in the order they were listed on the sheet
        for (Claim c : originals) {
            claims.relist(copies.get(c.getId()));
        }
    }

    /** Runs the insert, giving the generated id of the row it adds. */
    private Long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[] { "ID" });
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    private static Long id(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
