package net.officefloor.hq.app.sheet;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cloning a sheet to another id: a steward only, and only to an id never used. The clone gets the
 * sheet's today, settings, roles, rates, holidays, void reasons, templates, people (grades, terms,
 * contacts, aliases, holds, retirements and patrons), pools, periods (all open) and claims (status,
 * fixed figures, dates, notes, logs, comments and lines); each claim that had a number takes the clone's next,
 * in the order the claims were opened (people in list order on the same day). Statements, and so any
 * figure a statement bound, are not copied, nor are watchers, delegations or the sheet's history.
 */
@Service
public class CloneService {

    private final JdbcTemplate jdbc;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final RoleService roles;
    private final ClaimRepository claims;
    private final PeriodService periods;
    private final Rollups rollups;
    private final Audit audit;

    public CloneService(JdbcTemplate jdbc, SheetService sheets, SheetRepository sheetRows, RoleService roles,
            ClaimRepository claims, PeriodService periods, Rollups rollups, Audit audit) {
        this.jdbc = jdbc;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.roles = roles;
        this.claims = claims;
        this.periods = periods;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** Clone the sheet to another id; refused unless the user is a steward and the other id was never used. */
    @Transactional
    public synchronized void cloneTo(String sheetId, String user, String toSheetId) {
        sheets.lock(sheetId);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward clones the sheet.");
        }
        if (sheetRows.existsById(toSheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sheet " + toSheetId + " is already in use.");
        }

        jdbc.update("""
                INSERT INTO sheet (id, created_at, today, last_claim_number, clerk_limit, floor, terms, late_pct,
                    policy_date, period_numbering_from, last_unperiod_claim_number, terms_a, terms_b, terms_c, cap,
                    proof_threshold, grace_days, archived, entry_date, last_settle_batch, pot, parent_id)
                SELECT ?, CURRENT_TIMESTAMP, today, 0, clerk_limit, floor, terms, late_pct,
                    policy_date, period_numbering_from, 0, terms_a, terms_b, terms_c, cap,
                    proof_threshold, grace_days, FALSE, entry_date, last_settle_batch, pot, parent_id
                FROM sheet WHERE id = ?""", toSheetId, sheetId);
        copyRows("sheet_setting", "setting_key, from_date, setting_value", toSheetId, sheetId);
        copyRows("sheet_permission", "action, roles", toSheetId, sheetId);
        copyRows("sheet_role", "user_name, role", toSheetId, sheetId);
        copyRows("rate", "grade, pct, from_date, to_date", toSheetId, sheetId);
        copyRows("holiday", "holiday_date", toSheetId, sheetId);
        copyRows("void_reason", "reason", toSheetId, sheetId);
        copyRows("claim_template", "name, amount, note", toSheetId, sheetId);
        // Every period starts open, with nothing fixed and no numbers given out.
        copyRows("period", "name, name_key, from_date, to_date", toSheetId, sheetId);

        Map<Long, Long> people = new HashMap<>();
        for (Long id : jdbc.queryForList("SELECT id FROM person WHERE sheet_id = ? ORDER BY list_position, id",
                Long.class, sheetId)) {
            people.put(id, insert("""
                    INSERT INTO person (sheet_id, name, name_key, grade, list_position, terms, contact, tags, tags_used, retired_from,
                        origin_sheet_id)
                    SELECT ?, name, name_key, grade, list_position, terms, contact, tags, tags_used, retired_from, origin_sheet_id
                    FROM person WHERE id = ?""", toSheetId, id));
        }
        people.forEach((from, to) -> {
            copyChildren("person_grade", "person_id", "from_date, grade", to, from);
            copyChildren("person_hold", "person_id", "from_date, to_date", to, from);
            copyChildren("person_alias", "person_id", "alias, alias_key", to, from);
            copyChildren("person_retirement", "person_id", "retirement_order, from_date, to_date", to, from);
            jdbc.query("SELECT patron_id, patron_order FROM person_patron WHERE person_id = ?", row -> {
                jdbc.update("INSERT INTO person_patron (person_id, patron_id, patron_order) VALUES (?, ?, ?)", to,
                        people.get(row.getLong(1)), row.getInt(2));
            }, from);
        });

        Map<Long, Long> pools = new HashMap<>();
        for (Long id : jdbc.queryForList("SELECT id FROM pool WHERE sheet_id = ? ORDER BY id", Long.class, sheetId)) {
            pools.put(id, insert("INSERT INTO pool (sheet_id, name) SELECT ?, name FROM pool WHERE id = ?",
                    toSheetId, id));
        }
        jdbc.query("""
                SELECT m.pool_id, m.person_id, m.member_pool_id, m.share, m.from_date, m.to_date
                FROM pool_member m JOIN pool p ON p.id = m.pool_id WHERE p.sheet_id = ? ORDER BY m.id""", row -> {
            long person = row.getLong(2);
            Long personId = row.wasNull() ? null : people.get(person);
            long pool = row.getLong(3);
            Long memberPoolId = row.wasNull() ? null : pools.get(pool);
            jdbc.update("""
                    INSERT INTO pool_member (pool_id, person_id, member_pool_id, share, from_date, to_date)
                    VALUES (?, ?, ?, ?, ?, ?)""", pools.get(row.getLong(1)), personId, memberPoolId,
                    row.getBigDecimal(4), row.getDate(5), row.getDate(6));
        }, sheetId);

        // Claims are added in the order they are listed, so the clone lists them the same; none is
        // bound by a statement there, and none is held by an editor.
        Map<Long, Long> cloned = new HashMap<>();
        jdbc.query("SELECT id, person_id FROM claim WHERE sheet_id = ? ORDER BY listed, id", row -> {
            long from = row.getLong(1);
            long to = insert("""
                    INSERT INTO claim (sheet_id, person_id, amount, claim_date, note, status, settled_due, opened_by,
                        edited_by, settle_batch, corrects, due_by, terms, due_from, deferred_until, proofs, split_from,
                        late_entry, reminded_on, ref, ref_key, tags, tags_used, void_reason)
                    SELECT ?, ?, amount, claim_date, note, status, settled_due, opened_by,
                        edited_by, settle_batch, corrects, due_by, terms, due_from, deferred_until, proofs, split_from,
                        late_entry, reminded_on, ref, ref_key, tags, tags_used, void_reason
                    FROM claim WHERE id = ?""", toSheetId, people.get(row.getLong(2)), from);
            copyChildren("claim_log", "claim_id", "status, by_user, on_date, due", to, from);
            copyChildren("claim_comment", "claim_id", "by_user, on_date, text", to, from);
            copyChildren("claim_line", "claim_id", "label, qty, unit", to, from);
            cloned.put(to, from);
        }, sheetId);

        // Each claim that had a number takes the clone's next, in the order the originals were opened.
        Map<Long, String> numbers = new HashMap<>();
        jdbc.query("SELECT id, claim_number FROM claim WHERE sheet_id = ? AND claim_number IS NOT NULL",
                row -> {
                    numbers.put(row.getLong(1), row.getString(2));
                }, sheetId);
        Map<Long, Integer> positions = new HashMap<>();
        jdbc.query("SELECT id, list_position FROM person WHERE sheet_id = ?", row -> {
            positions.put(row.getLong(1), row.getInt(2));
        }, toSheetId);
        List<Claim> numbered = claims.findBySheetIdOrderByIdDesc(toSheetId).stream()
                .filter(claim -> numbers.containsKey(cloned.get(claim.getId())))
                .sorted(Comparator.comparing((Claim claim) -> claim.openedOn(),
                        Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                        .thenComparing(claim -> positions.get(claim.getPersonId()))
                        .thenComparing(claim -> cloned.get(claim.getId())))
                .toList();
        Map<String, String> renumbered = new HashMap<>();
        for (Claim claim : numbered) {
            renumbered.put(numbers.get(cloned.get(claim.getId())), periods.nextClaimNumber(toSheetId, claim.getDueBy()));
        }
        List<Claim> all = claims.findBySheetIdOrderByIdDesc(toSheetId);
        for (Claim claim : all) {
            String number = numbers.get(cloned.get(claim.getId()));
            if (number != null) {
                claim.number(renumbered.get(number));
            }
            claim.renumberLinks(renumbered);
        }
        claims.saveAllAndFlush(all);

        audit.recordOnCommit("SHEET_CLONE_MADE sheet=" + sheetId + " by=" + user + " to=" + toSheetId);
        audit.recordOnCommit("SHEET_CLONED sheet=" + toSheetId + " by=" + user + " from=" + sheetId);
        // Every person's figures and the totals are new on the clone.
        rollups.audit(toSheetId, user, new Rollups.Rollup(Map.of(), null, null, null));
    }

    /** Copy a sheet's rows of a table to the clone, with the columns named. */
    private void copyRows(String table, String columns, String toSheetId, String sheetId) {
        jdbc.update("INSERT INTO " + table + " (sheet_id, " + columns + ") SELECT ?, " + columns + " FROM " + table
                + " WHERE sheet_id = ?" + (hasId(table) ? " ORDER BY id" : ""), toSheetId, sheetId);
    }

    /** Copy the rows of a child table from one parent row to another, oldest first. */
    private void copyChildren(String table, String key, String columns, long to, long from) {
        jdbc.update("INSERT INTO " + table + " (" + key + ", " + columns + ") SELECT ?, " + columns + " FROM " + table
                + " WHERE " + key + " = ?" + (hasId(table) ? " ORDER BY id" : ""), to, from);
    }

    /** Whether a table has its own id column to keep its rows' order by. */
    private static boolean hasId(String table) {
        return !List.of("sheet_setting", "sheet_permission", "person_grade", "person_hold", "person_alias",
                "person_retirement").contains(table);
    }

    /** Insert one row, answering its generated id. */
    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[] { "ID" });
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }
}
