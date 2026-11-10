package net.officefloor.hq.app.sheet;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cloning a sheet to another id that was never used (a steward only). The clone gets the sheet's
 * today, settings, roles, rates, holidays, void reasons, templates, people (grades, terms, contacts,
 * aliases, tags, holds, retirements, patrons), pools, crews, periods (all open) and claims with their
 * status, frozen figures, dates, notes and logs. Each claim that had a number is numbered afresh by
 * the clone's own numbering, in the order the claims were opened (by the sheet's today when each was
 * opened, then by the order they were added to the clone: by person in list order, each person's in
 * the order they were listed). Statements are not copied, so no figure is bound by one; nor are
 * watches, delegations or the sheet's history.
 *
 * <p>Rows are copied column by column as the schema has them, so a column added later is carried
 * without change here; only the columns that need another value are named.
 */
@Service
public class SheetCloneService {

    /** Columns the store gives a new row its own value for. */
    private static final Set<String> GENERATED = Set.of("ID", "LISTED");

    /** The sheet's own tables copied as they are, onto the clone. */
    private static final List<String> SHEET_TABLES = List.of("SHEET_SETTING", "SHEET_SETTING_LOG",
            "SHEET_PERMISSION", "SHEET_ROLE", "RATE", "RATE_LOG", "RATE_VERSION", "HOLIDAY", "VOID_REASON",
            "CLAIM_TEMPLATE", "CLAIM_TAG_USED");

    /** A person's own tables, copied onto their clone. */
    private static final List<String> PERSON_TABLES = List.of("PERSON_GRADE", "PERSON_TERMS", "PERSON_HOLD", "PERSON_ALIAS",
            "PERSON_RETIREMENT", "PERSON_SNAPSHOT");

    /** A claim's own tables, copied onto its clone. */
    private static final List<String> CLAIM_TABLES = List.of("CLAIM_LOG", "CLAIM_COMMENT", "CLAIM_LINE");

    private final JdbcTemplate jdbc;
    private final SheetService sheets;
    private final SheetHistory history;
    private final RoleService roles;
    private final RollupService rollup;
    private final Audit audit;
    private final Map<String, List<String>> columns = new ConcurrentHashMap<>();

    public SheetCloneService(JdbcTemplate jdbc, SheetService sheets, SheetHistory history, RoleService roles,
            RollupService rollup, Audit audit) {
        this.jdbc = jdbc;
        this.sheets = sheets;
        this.history = history;
        this.roles = roles;
        this.rollup = rollup;
        this.audit = audit;
    }

    /**
     * Clone the sheet to {@code toSheetId}. Refused (403) to anyone but a steward, (409) when the
     * other sheet was ever used.
     */
    @Transactional
    public Sheet cloneTo(String sheetId, String user, String toSheetId) {
        roles.refuseUnlessSteward(sheetId, user);
        // hold the sheet so nothing changes on it while it is copied
        sheets.lock(sheetId);
        Long used = jdbc.queryForObject("SELECT COUNT(*) FROM sheet WHERE id = ?", Long.class, toSheetId);
        if (used != null && used > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sheet " + toSheetId + " is already in use.");
        }

        copy("SHEET", "id = ?", sheetId, "", Map.of("ID", v -> toSheetId, "CREATED_BY", v -> user,
                "CREATED_AT", v -> Timestamp.from(Instant.now()), "LAST_CLAIM_NUMBER", v -> 0,
                "LAST_UNPERIOD_CLAIM_NUMBER", v -> 0, "ARCHIVED", v -> false));
        UnaryOperator<Object> onClone = v -> toSheetId;
        for (String table : SHEET_TABLES) {
            copy(table, "sheet_id = ?", sheetId, order(table), Map.of("SHEET_ID", onClone));
        }

        Map<Long, Long> people = copy("PERSON", "sheet_id = ?", sheetId, "ORDER BY id",
                Map.of("SHEET_ID", onClone, "VERSION", v -> 0));
        UnaryOperator<Object> person = v -> v == null ? null : people.get(((Number) v).longValue());
        for (String table : PERSON_TABLES) {
            copy(table, "person_id IN (SELECT id FROM person WHERE sheet_id = ?)", sheetId, "",
                    Map.of("PERSON_ID", person));
        }
        copy("PERSON_PATRON", "sheet_id = ?", sheetId, "ORDER BY id",
                Map.of("SHEET_ID", onClone, "PERSON_ID", person, "PATRON_ID", person));

        Map<Long, Long> periodIds = copy("PERIOD", "sheet_id = ?", sheetId, "ORDER BY id", Map.of("SHEET_ID", onClone,
                "CLOSED", v -> false, "SOFT_CLOSED", v -> false, "CLOSED_DUE", v -> null, "LAST_CLAIM_NUMBER", v -> 0));
        copy("PERIOD_PERSON_OPENING", "period_id IN (SELECT id FROM period WHERE sheet_id = ?)", sheetId, "",
                Map.of("PERIOD_ID", v -> periodIds.get(((Number) v).longValue()), "PERSON_ID", person));

        Map<Long, Long> pools = copy("POOL", "sheet_id = ?", sheetId, "ORDER BY id", Map.of("SHEET_ID", onClone));
        UnaryOperator<Object> pool = v -> v == null ? null : pools.get(((Number) v).longValue());
        copy("POOL_MEMBER", "sheet_id = ?", sheetId, "ORDER BY id",
                Map.of("SHEET_ID", onClone, "POOL_ID", pool, "PERSON_ID", person, "MEMBER_POOL_ID", pool));
        Map<Long, Long> crews = copy("CREW", "sheet_id = ?", sheetId, "ORDER BY id",
                Map.of("SHEET_ID", onClone, "LEAD_ID", person));
        UnaryOperator<Object> crew = v -> v == null ? null : crews.get(((Number) v).longValue());
        copy("CREW_MEMBER", "sheet_id = ?", sheetId, "ORDER BY id",
                Map.of("SHEET_ID", onClone, "CREW_ID", crew, "PERSON_ID", person));

        // the claims arrive person by person in list order, each person's in the order they were listed
        Map<Long, Long> claims = copy("CLAIM", "sheet_id = ?", sheetId,
                "ORDER BY (SELECT p.position FROM person p WHERE p.id = claim.person_id), person_id, listed",
                Map.of("SHEET_ID", onClone, "PERSON_ID", person, "CLAIM_NUMBER", v -> null, "STATED", v -> false,
                        "STATED_DUE", v -> null, "LOCKED_BY", v -> null));
        UnaryOperator<Object> claim = v -> claims.get(((Number) v).longValue());
        for (String table : CLAIM_TABLES) {
            copy(table, "claim_id IN (SELECT id FROM claim WHERE sheet_id = ?)", sheetId, "ORDER BY claim_id, seq",
                    Map.of("CLAIM_ID", claim));
        }
        renumber(sheetId, toSheetId, claims);

        history.captureFirst(toSheetId);
        audit.recordOnCommit("SHEET_CLONE_MADE sheet=" + sheetId + " by=" + user + " to=" + toSheetId);
        audit.recordOnCommit("SHEET_CLONED sheet=" + toSheetId + " by=" + user + " from=" + sheetId);
        rollup.recordAll(toSheetId, user);
        return sheets.sheet(toSheetId);
    }

    /**
     * Number the clone's claims that had a number, by the clone's own numbering, in the order they
     * were opened (the sheet's today when first opened, then the order they were added to the clone),
     * and point each correction and split at the new number of the claim it named.
     */
    private void renumber(String sheetId, String toSheetId, Map<Long, Long> claims) {
        record Numbered(long id, String number, LocalDate dueBy, LocalDate openedOn) {
        }
        Map<Long, LocalDate> openedOn = new HashMap<>();
        jdbc.query("SELECT claim_id, log_date FROM claim_log WHERE claim_id IN (SELECT id FROM claim WHERE sheet_id = ?)"
                + " AND status NOT IN ('DRAFT', 'DELETED') ORDER BY claim_id, seq", (ResultSet rs) -> {
                    openedOn.putIfAbsent(rs.getLong(1), rs.getObject(2, LocalDate.class));
                }, sheetId);
        List<Numbered> numbered = jdbc.query(
                "SELECT id, claim_number, due_by FROM claim WHERE sheet_id = ? AND claim_number IS NOT NULL",
                (rs, n) -> new Numbered(claims.get(rs.getLong(1)), rs.getString(2),
                        rs.getObject(3, LocalDate.class), openedOn.get(rs.getLong(1))),
                sheetId);
        Map<String, String> numbers = new HashMap<>();
        numbered.stream()
                .sorted(Comparator.comparing(Numbered::openedOn, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Numbered::id))
                .forEach(c -> {
                    String number = sheets.nextClaimNumber(toSheetId, c.dueBy());
                    jdbc.update("UPDATE claim SET claim_number = ? WHERE id = ?", number, c.id());
                    numbers.put(c.number(), number);
                });
        jdbc.query("SELECT id, corrects, split_from FROM claim WHERE sheet_id = ?"
                + " AND (corrects IS NOT NULL OR split_from IS NOT NULL)", (ResultSet rs) -> {
                    String corrects = rs.getString(2);
                    String splitFrom = rs.getString(3);
                    jdbc.update("UPDATE claim SET corrects = ?, split_from = ? WHERE id = ?",
                            numbers.getOrDefault(corrects, corrects), numbers.getOrDefault(splitFrom, splitFrom),
                            rs.getLong(1));
                }, toSheetId);
    }

    /** The order a sheet table's rows are copied in: as they were made. */
    private String order(String table) {
        List<String> cols = columns(table);
        return cols.contains("ID") ? "ORDER BY id" : cols.contains("SEQ") ? "ORDER BY seq" : "";
    }

    /**
     * Copy the rows of a table picked by {@code where} (one parameter), in {@code order}, each with
     * the named columns given new values from their old; answers each old row's id with its copy's
     * (none for a table without generated ids).
     */
    private Map<Long, Long> copy(String table, String where, Object arg, String order,
            Map<String, UnaryOperator<Object>> set) {
        List<String> all = columns(table);
        boolean generatedId = all.contains("ID") && !set.containsKey("ID");
        List<String> kept = all.stream().filter(c -> set.containsKey(c) || !GENERATED.contains(c)).toList();
        List<String> read = new ArrayList<>(kept);
        if (generatedId) {
            read.add(0, "ID");
        }
        List<Map<String, Object>> rows = jdbc.query("SELECT " + String.join(", ", read) + " FROM " + table
                + " WHERE " + where + " " + order, (ResultSet rs) -> {
                    List<Map<String, Object>> found = new ArrayList<>();
                    ResultSetMetaData meta = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            row.put(meta.getColumnName(i), rs.getObject(i));
                        }
                        found.add(row);
                    }
                    return found;
                }, arg);
        String sql = "INSERT INTO " + table + " (" + String.join(", ", kept) + ") VALUES ("
                + kept.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
        Map<Long, Long> ids = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object[] values = kept.stream()
                    .map(c -> set.containsKey(c) ? set.get(c).apply(row.get(c)) : row.get(c)).toArray();
            if (!generatedId) {
                jdbc.update(sql, values);
                continue;
            }
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, new String[] { "ID" });
                for (int i = 0; i < values.length; i++) {
                    ps.setObject(i + 1, values[i]);
                }
                return ps;
            }, key);
            ids.put(((Number) row.get("ID")).longValue(), key.getKey().longValue());
        }
        return ids;
    }

    /** A table's columns that are stored (not generated), in order. */
    private List<String> columns(String table) {
        return columns.computeIfAbsent(table, t -> jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND IS_GENERATED = 'NEVER' ORDER BY ORDINAL_POSITION",
                String.class, t));
    }
}
