package net.officefloor.hq.app.asof;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole sheet as it stood at the end of each day it had as its today: when its today moves on, every
 * row the sheet has in every table is written down as it is then. The sheet as of a date is the last one
 * written for a day on or before it (every change made while its today was on or before that date, and none
 * made later), or the sheet as it stands when the date is on or after its today; none written by then is an
 * empty sheet. It is shown by putting it in the store in place of the sheet within a transaction that is
 * always rolled back, with the date as the sheet's today, so every figure is read exactly as for the live
 * sheet and nothing of it is kept, announced or audited.
 *
 * <p>A table added later that holds a sheet's rows is listed in {@link #TABLES}.
 */
@Service
public class SheetPasts {

    /** A table of a sheet's rows, and which of its rows are the sheet's. */
    private record Scope(String table, String where) {
    }

    /** Every table holding a sheet's rows, each after the tables it refers to. */
    private static final List<Scope> TABLES = List.of(
            new Scope("PERSON", "sheet_id = ?"),
            new Scope("PERSON_GRADE", "person_id IN (SELECT id FROM person WHERE sheet_id = ?)"),
            new Scope("PERSON_HOLD", "person_id IN (SELECT id FROM person WHERE sheet_id = ?)"),
            new Scope("PERSON_RETIREMENT", "person_id IN (SELECT id FROM person WHERE sheet_id = ?)"),
            new Scope("PERSON_ALIAS", "person_id IN (SELECT id FROM person WHERE sheet_id = ?)"),
            new Scope("PERSON_PATRON", "sheet_id = ?"),
            new Scope("PERSON_WATCH", "sheet_id = ?"),
            new Scope("CLAIM", "sheet_id = ?"),
            new Scope("CLAIM_LINE", "claim_id IN (SELECT id FROM claim WHERE sheet_id = ?)"),
            new Scope("CLAIM_LOG", "claim_id IN (SELECT id FROM claim WHERE sheet_id = ?)"),
            new Scope("CLAIM_COMMENT", "claim_id IN (SELECT id FROM claim WHERE sheet_id = ?)"),
            new Scope("CLAIM_RETIRED_NUMBER", "sheet_id = ?"),
            new Scope("RATE", "sheet_id = ?"),
            new Scope("SHEET_SETTING", "sheet_id = ?"),
            new Scope("SHEET_ROLE", "sheet_id = ?"),
            new Scope("DELEGATION", "sheet_id = ?"),
            new Scope("PERIOD", "sheet_id = ?"),
            new Scope("PERIOD_PERSON_TOTAL", "period_id IN (SELECT id FROM period WHERE sheet_id = ?)"),
            new Scope("STATEMENT", "sheet_id = ?"),
            new Scope("SHEET_TAG", "sheet_id = ?"),
            new Scope("SHEET_VOID_REASON", "sheet_id = ?"),
            new Scope("POOL", "sheet_id = ?"),
            new Scope("POOL_MEMBER", "pool_id IN (SELECT id FROM pool WHERE sheet_id = ?)"),
            new Scope("SHEET_PLAN", "sheet_id = ?"),
            new Scope("SHEET_PLAN_CLAIM", "plan_id IN (SELECT id FROM sheet_plan WHERE sheet_id = ?)"));

    /** The sheet's own columns that stay as they are: its key, who started it and its today. */
    private static final List<String> SHEET_KEPT = List.of("ID", "CREATED_BY", "TODAY");

    /** Every row of a sheet, as text by column: the sheet's own row and each table's rows. */
    record Rows(Map<String, String> sheet, Map<String, List<Map<String, String>>> tables) {
    }

    private record Column(String name, boolean nullable, boolean defaulted) {
    }

    private final SheetPastRepository pasts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate rolledBack;
    private final JsonMapper json;
    private final Map<String, List<Column>> columns = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> shown = new ConcurrentHashMap<>();

    public SheetPasts(SheetPastRepository pasts, JdbcTemplate jdbc, PlatformTransactionManager transactions,
            JsonMapper json) {
        this.pasts = pasts;
        this.jdbc = jdbc;
        this.rolledBack = new TransactionTemplate(transactions);
        this.json = json;
    }

    /** Write the sheet down as it stands now, as at the end of the day given (the today it is leaving). */
    @Transactional
    public void record(String sheetId, LocalDate day) {
        List<Map<String, String>> sheet = select("SHEET", "id = ?", sheetId);
        if (sheet.isEmpty()) {
            return;
        }
        Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
        for (Scope scope : TABLES) {
            tables.put(scope.table(), select(scope.table(), scope.where(), sheetId));
        }
        pasts.saveAndFlush(new SheetPast(sheetId, day, json.writeValueAsString(new Rows(sheet.get(0), tables))));
    }

    /**
     * Run the read with the sheet shown as it stood at the end of the date, that date as its today; nothing
     * the read does is kept. A sheet never started is read as it is.
     */
    public <T> T asOf(String sheetId, LocalDate date, Supplier<T> read) {
        ReentrantLock lock = shown.computeIfAbsent(sheetId, k -> new ReentrantLock());
        lock.lock();
        try {
            return rolledBack.execute(status -> {
                status.setRollbackOnly();
                put(sheetId, date);
                return read.get();
            });
        } finally {
            lock.unlock();
        }
    }

    private void put(String sheetId, LocalDate date) {
        List<LocalDate> today = jdbc.queryForList("SELECT today FROM sheet WHERE id = ?", LocalDate.class, sheetId);
        if (today.isEmpty()) {
            return;
        }
        if (date.isBefore(today.get(0))) {
            Optional<Rows> then = pasts.findFirstBySheetIdAndEndedOnLessThanEqualOrderByIdDesc(sheetId, date)
                    .map(p -> json.readValue(p.getState(), new TypeReference<Rows>() {
                    }));
            for (int i = TABLES.size() - 1; i >= 0; i--) {
                Scope scope = TABLES.get(i);
                jdbc.update("DELETE FROM " + scope.table() + " WHERE " + scope.where(), sheetId);
            }
            if (then.isPresent()) {
                putSheet(sheetId, then.get().sheet());
                for (Scope scope : TABLES) {
                    then.get().tables().getOrDefault(scope.table(), List.of()).forEach(row -> insert(scope.table(), row));
                }
            } else {
                startSheet(sheetId);
            }
        }
        jdbc.update("UPDATE sheet SET today = ? WHERE id = ?", date, sheetId);
    }

    /** The sheet's own row as it was. */
    private void putSheet(String sheetId, Map<String, String> row) {
        List<String> names = columns("SHEET").stream().map(Column::name)
                .filter(c -> !SHEET_KEPT.contains(c) && row.containsKey(c)).toList();
        if (names.isEmpty()) {
            return;
        }
        List<Object> values = new ArrayList<>();
        names.forEach(c -> values.add(row.get(c)));
        values.add(sheetId);
        jdbc.update("UPDATE sheet SET " + names.stream().map(c -> c + " = ?").collect(Collectors.joining(", "))
                + " WHERE id = ?", values.toArray());
    }

    /** The sheet's own row as it was when it was started: every column at its default. */
    private void startSheet(String sheetId) {
        List<String> sets = columns("SHEET").stream().filter(c -> !SHEET_KEPT.contains(c.name()))
                .filter(c -> c.defaulted() || c.nullable())
                .map(c -> c.name() + (c.defaulted() ? " = DEFAULT" : " = NULL")).toList();
        if (!sets.isEmpty()) {
            jdbc.update("UPDATE sheet SET " + String.join(", ", sets) + " WHERE id = ?", sheetId);
        }
    }

    private void insert(String table, Map<String, String> row) {
        List<String> names = columns(table).stream().map(Column::name).filter(row::containsKey).toList();
        jdbc.update("INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES ("
                + names.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")",
                names.stream().map(row::get).toArray());
    }

    private List<Map<String, String>> select(String table, String where, String sheetId) {
        List<String> names = columns(table).stream().map(Column::name).toList();
        return jdbc.query("SELECT " + String.join(", ", names) + " FROM " + table + " WHERE " + where,
                (rs, n) -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 0; i < names.size(); i++) {
                        row.put(names.get(i), rs.getString(i + 1));
                    }
                    return row;
                }, sheetId);
    }

    /** The table's columns that are written (not computed from the others), in order. */
    private List<Column> columns(String table) {
        return columns.computeIfAbsent(table, t -> jdbc.query(
                "SELECT column_name, is_nullable, column_default FROM information_schema.columns"
                        + " WHERE table_schema = 'PUBLIC' AND table_name = ? AND is_generated = 'NEVER'"
                        + " ORDER BY ordinal_position",
                (rs, n) -> new Column(rs.getString(1), "YES".equals(rs.getString(2)), rs.getString(3) != null), t));
    }
}
