package net.officefloor.hq.app.sheet;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.asof.SheetHistory;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A steward clones the sheet to another id, one never used: the clone starts with this sheet's today,
 * settings, roles, rates, holidays, void reasons, templates, people (with their grades, terms, contacts,
 * aliases, holds, retirements, snapshot lines and patrons), pools, periods (all open) and claims (with their status,
 * frozen figures, dates, notes, tags and logs, numbered afresh by the clone's own numbering). Statements,
 * and the figures they bound, are not copied; nor are watches, delegations or the sheet's parent. Rows are
 * copied column by column as the store holds them, so a column added later is copied too. The clone remembers
 * this sheet as its source: a grade with no rate in force on the clone nor its parents takes the source's.
 */
@Service
public class SheetCloneService {

    private final JdbcTemplate jdbc;
    private final SheetRepository sheets;
    private final SettingService settings;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RollupService rollup;
    private final SheetHistory history;
    private final SheetSourceRepository sources;
    private final Audit audit;

    public SheetCloneService(DataSource dataSource, SheetRepository sheets, SettingService settings,
            ClaimRepository claims, ClaimService claimService, RollupService rollup, SheetHistory history,
            SheetSourceRepository sources, Audit audit) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.sheets = sheets;
        this.settings = settings;
        this.claims = claims;
        this.claimService = claimService;
        this.rollup = rollup;
        this.history = history;
        this.sources = sources;
        this.audit = audit;
    }

    /**
     * Clone the sheet to the other id. Refused unless the user is a steward and the other sheet was never
     * used. This sheet audits SHEET_CLONE_MADE; the clone's first line is SHEET_CLONED, followed by the
     * rollup lines of every person and its totals.
     */
    @Transactional
    public Sheet cloneTo(String sheetId, String user, String toSheetId) {
        String by = user.strip();
        Sheet from = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (settings.roleOf(sheetId, by) != Role.STEWARD) throw new SheetCloneRefusedException();
        if (sheetId.equals(toSheetId) || sheets.existsById(toSheetId)) throw new SheetCloneRefusedException();

        // the sheet itself: a new sheet with this one's today and undated settings, no numbers given yet
        Map<String, Object> sheet = one("SELECT * FROM sheet WHERE id = ?", sheetId);
        sheet.put("ID", toSheetId);
        sheet.put("CREATED_BY", by);
        sheet.put("CREATED_AT", Timestamp.from(Instant.now()));
        sheet.put("LAST_CLAIM_NUMBER", 0L);
        sheet.put("ARCHIVED", false);
        insert("sheet", sheet);
        // the sheet it was cloned from, whose rates (then its parents' and source's) a grade with none in force takes
        sources.saveAndFlush(new SheetSource(toSheetId, sheetId));

        for (String table : List.of("sheet_setting", "sheet_setting_log", "sheet_permission", "sheet_role", "rate",
                "rate_log", "holiday", "void_reason", "claim_template", "claim_tag", "sheet_grade")) {
            for (Map<String, Object> row : rows("SELECT * FROM " + table + " WHERE sheet_id = ? ORDER BY 1, 2", sheetId)) {
                row.put("SHEET_ID", toSheetId);
                if (row.containsKey("ID")) insertNew(table, row);
                else insert(table, row);
            }
        }

        // the people, in list order, with what hangs off each
        Map<Long, Long> people = new HashMap<>();
        for (Map<String, Object> row : rows("SELECT * FROM person WHERE sheet_id = ? AND merged_into IS NULL"
                + " ORDER BY list_position, id", sheetId)) {
            Long old = ((Number) row.get("ID")).longValue();
            row.put("SHEET_ID", toSheetId);
            row.put("VERSION", 0L);
            long id = insertNew("person", row);
            people.put(old, id);
            for (String table : List.of("person_grade", "person_grade_log", "person_terms", "person_hold", "person_retirement",
                    "person_snapshot")) {
                for (Map<String, Object> line : rows("SELECT * FROM " + table + " WHERE person_id = ?", old)) {
                    line.put("PERSON_ID", id);
                    insert(table, line);
                }
            }
            for (Map<String, Object> line : rows("SELECT * FROM person_alias WHERE person_id = ?", old)) {
                line.put("PERSON_ID", id);
                line.put("SHEET_ID", toSheetId);
                insert("person_alias", line);
            }
        }
        for (Map<String, Object> row : rows("SELECT * FROM person_patron WHERE sheet_id = ? ORDER BY id", sheetId)) {
            Long person = people.get(((Number) row.get("PERSON_ID")).longValue());
            Long patron = people.get(((Number) row.get("PATRON_ID")).longValue());
            if (person == null || patron == null) continue;
            row.put("SHEET_ID", toSheetId);
            row.put("PERSON_ID", person);
            row.put("PATRON_ID", patron);
            insertNew("person_patron", row);
        }

        // the pools and their members
        Map<Long, Long> pools = new HashMap<>();
        for (Map<String, Object> row : rows("SELECT * FROM pool WHERE sheet_id = ? ORDER BY id", sheetId)) {
            Long old = ((Number) row.get("ID")).longValue();
            row.put("SHEET_ID", toSheetId);
            pools.put(old, insertNew("pool", row));
        }
        List<Map<String, Object>> members = rows("SELECT * FROM pool_member WHERE sheet_id = ? ORDER BY id", sheetId);
        for (Map<String, Object> row : members) {
            Long pool = pools.get(((Number) row.get("POOL_ID")).longValue());
            Long person = row.get("PERSON_ID") == null ? null : people.get(((Number) row.get("PERSON_ID")).longValue());
            Long member = row.get("MEMBER_POOL_ID") == null ? null
                    : pools.get(((Number) row.get("MEMBER_POOL_ID")).longValue());
            if (pool == null || (person == null && member == null)) continue;
            row.put("SHEET_ID", toSheetId);
            row.put("POOL_ID", pool);
            row.put("PERSON_ID", person);
            row.put("MEMBER_POOL_ID", member);
            insertNew("pool_member", row);
        }

        // the crews and their members
        Map<Long, Long> crews = new HashMap<>();
        for (Map<String, Object> row : rows("SELECT * FROM crew WHERE sheet_id = ? ORDER BY id", sheetId)) {
            Long old = ((Number) row.get("ID")).longValue();
            Long lead = people.get(((Number) row.get("LEAD_PERSON_ID")).longValue());
            if (lead == null) continue;
            row.put("SHEET_ID", toSheetId);
            row.put("LEAD_PERSON_ID", lead);
            crews.put(old, insertNew("crew", row));
        }
        for (Map<String, Object> row : rows("SELECT * FROM crew_member WHERE sheet_id = ? ORDER BY id", sheetId)) {
            Long crew = crews.get(((Number) row.get("CREW_ID")).longValue());
            Long person = people.get(((Number) row.get("PERSON_ID")).longValue());
            if (crew == null || person == null) continue;
            row.put("SHEET_ID", toSheetId);
            row.put("CREW_ID", crew);
            row.put("PERSON_ID", person);
            insertNew("crew_member", row);
        }

        // the periods, all open
        for (Map<String, Object> row : rows("SELECT * FROM period WHERE sheet_id = ? ORDER BY id", sheetId)) {
            row.put("SHEET_ID", toSheetId);
            row.put("CLOSED", false);
            row.put("SOFT_CLOSED", false);
            row.put("LOCKED", false);
            row.put("CLOSED_DUE_TOTAL", null);
            insertNew("period", row);
        }

        // the claims, person by person in list order, each listed as it was; bound by no statement, unnumbered
        Map<Long, String> oldNumbers = new HashMap<>();
        for (Map.Entry<Long, Long> person : people.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).toList()) {
            for (Map<String, Object> row : rows("SELECT * FROM claim WHERE sheet_id = ? AND person_id = ?"
                    + " ORDER BY listed, id", sheetId, person.getKey())) {
                Long old = ((Number) row.get("ID")).longValue();
                String number = (String) row.get("CLAIM_NUMBER");
                row.remove("LISTED");
                row.put("SHEET_ID", toSheetId);
                row.put("PERSON_ID", person.getValue());
                row.put("CLAIM_NUMBER", null);
                row.put("NUMBER_PERIOD", null);
                row.put("NUMBER_SEQ", null);
                row.put("STATED", false);
                row.put("STATED_DUE", null);
                row.put("LOCKED_BY", null);
                long id = insertNew("claim", row);
                oldNumbers.put(id, number);
                for (Map<String, Object> line : rows("SELECT * FROM claim_log WHERE claim_id = ?", old)) {
                    line.put("CLAIM_ID", id);
                    insert("claim_log", line);
                }
            }
        }

        // numbered afresh by the clone's own numbering
        Sheet clone = sheets.findForUpdate(toSheetId).orElseThrow(SheetNotFoundException::new);
        List<Claim> cloned = new ArrayList<>(claims.findBySheetIdOrderByIdDesc(toSheetId));
        claimService.numberAfresh(clone, cloned, oldNumbers);

        audit.recordOnCommit("SHEET_CLONE_MADE sheet=" + sheetId + " by=" + by + " to=" + toSheetId);
        audit.recordOnCommit("SHEET_CLONED sheet=" + toSheetId + " by=" + by + " from=" + sheetId);
        rollup.recordChanges(toSheetId, by, null);
        history.capture(toSheetId);
        return from;
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> found = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) found.add(new LinkedHashMap<>(row));
        return found;
    }

    private Map<String, Object> one(String sql, Object... args) {
        return rows(sql, args).get(0);
    }

    /** Insert the row as given. */
    private void insert(String table, Map<String, Object> row) {
        jdbc.update(insertSql(table, row), row.values().toArray());
    }

    /** Insert the row under a new id, given by the store; returns it. */
    private long insertNew(String table, Map<String, Object> row) {
        row.remove("ID");
        String sql = insertSql(table, row);
        Object[] args = row.values().toArray();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[] {"ID"});
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? 0 : id.longValue();
    }

    private static String insertSql(String table, Map<String, Object> row) {
        List<String> cols = row.keySet().stream().map(c -> "\"" + c + "\"").toList();
        return "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", cols.stream().map(c -> "?").toList()) + ")";
    }
}
