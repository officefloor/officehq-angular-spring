package net.officefloor.hq.app.sheet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet as it stood after each change, so it can be shown as of a date. After every change the
 * whole of the sheet's stored rows are kept (in {@code sheet_snapshot}) with the sheet's today then;
 * {@link #restore} puts the last one made on or before a date back in place of the sheet's rows,
 * its today the date, for the caller's transaction to read and then roll back.
 *
 * <p>The sheet's rows are found from the schema: every table with a {@code sheet_id}, and every
 * table without one that hangs off a person, claim, period or plan of the sheet. A table added later is
 * so kept without change here.
 */
@Service
public class SheetHistory {

    /**
     * Tables never kept: the sheet row itself is kept apart, and the snapshots are the history. The
     * sheet's notes are dated by the today each was added on, and shown as of a date by that.
     */
    private static final Set<String> SKIPPED = Set.of("SHEET", "SHEET_SNAPSHOT", "SHEET_NOTE",
            "flyway_schema_history");

    /** The sheet's columns not kept: they are not dated, so a sheet shown as of a date shows them as they are now. */
    private static final Set<String> UNKEPT_SHEET_COLUMNS = Set.of("PARENT_ID", "ADDED_GRADES");

    /** The tables others refer to, deleted last and put back first (in this order of putting back). */
    private static final List<String> PARENTS = List.of("PERSON", "PERIOD", "CLAIM");

    /** A kept table: its name, the clause picking the sheet's rows (one parameter, the sheet's id) and its columns. */
    private record Table(String name, String where, List<String> columns) {
    }

    private final JdbcTemplate jdbc;
    private volatile List<Table> tables;
    private volatile List<String> sheetColumns;

    public SheetHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Keep the sheet as the store holds it now, made on its today; nothing when there is no such sheet. */
    @Transactional
    public void capture(String sheetId) {
        // hold the sheet so no change lands between reading its rows and its today
        List<LocalDate> today = jdbc.query("SELECT today FROM sheet WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getObject(1, LocalDate.class), sheetId);
        if (today.isEmpty()) {
            return;
        }
        save(sheetId, today.get(0));
    }

    /** Keep the sheet as it is now as counting from the beginning of time, when nothing is kept for it yet. */
    @Transactional
    public void captureFirst(String sheetId) {
        if (jdbc.query("SELECT id FROM sheet WHERE id = ? FOR UPDATE", (rs, n) -> rs.getString(1), sheetId).isEmpty()) {
            return;
        }
        Long kept = jdbc.queryForObject("SELECT COUNT(*) FROM sheet_snapshot WHERE sheet_id = ?", Long.class, sheetId);
        if (kept == null || kept == 0) {
            save(sheetId, null);
        }
    }

    /**
     * Put the sheet's today at a preview date over its current records, for the caller's transaction
     * to read and then roll back; nothing when there is no such sheet.
     */
    public void preview(String sheetId, LocalDate date) {
        jdbc.update("UPDATE sheet SET today = ? WHERE id = ?", date, sheetId);
    }

    /**
     * Within the caller's transaction, replace the sheet's rows by the last snapshot made on or
     * before {@code asOf} and set its today to {@code asOf}. The caller must roll back.
     */
    @Transactional
    public void restore(String sheetId, LocalDate asOf) {
        if (jdbc.query("SELECT id FROM sheet WHERE id = ? FOR UPDATE", (rs, n) -> rs.getString(1), sheetId).isEmpty()) {
            return;
        }
        List<byte[]> found = jdbc.query("SELECT data FROM sheet_snapshot WHERE sheet_id = ?"
                + " AND (made_on IS NULL OR made_on <= ?) ORDER BY seq DESC LIMIT 1",
                (rs, n) -> rs.getBytes(1), sheetId, asOf);
        Map<String, List<List<Object>>> data = found.isEmpty() ? Map.of() : read(found.get(0));
        List<Table> all = tables();
        for (int i = all.size() - 1; i >= 0; i--) {
            Table t = all.get(i);
            jdbc.update("DELETE FROM " + t.name() + " WHERE " + t.where(), sheetId);
        }
        for (Table t : all) {
            List<List<Object>> rows = data.get(t.name());
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            List<String> columns = rows.get(0).stream().map(String::valueOf).toList();
            String sql = "INSERT INTO " + t.name() + " (" + String.join(", ", columns) + ") VALUES ("
                    + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
            for (List<Object> row : rows.subList(1, rows.size())) {
                jdbc.update(sql, row.toArray());
            }
        }
        List<List<Object>> sheet = data.get("SHEET");
        if (sheet != null && sheet.size() == 2) {
            List<String> columns = sheet.get(0).stream().map(String::valueOf).toList();
            List<Object> values = new ArrayList<>(sheet.get(1));
            values.add(sheetId);
            jdbc.update("UPDATE sheet SET " + columns.stream().map(c -> c + " = ?").collect(Collectors.joining(", "))
                    + " WHERE id = ?", values.toArray());
        }
        jdbc.update("UPDATE sheet SET today = ? WHERE id = ?", asOf, sheetId);
        // a claim editor's lock is this moment's, not the sheet's history: no editor is open as of a date
        jdbc.update("UPDATE claim SET locked_by = NULL WHERE sheet_id = ?", sheetId);
    }

    /** Read the schema once the app is up, so the first change is not slowed by it. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        tables();
        sheetColumns();
    }

    private void save(String sheetId, LocalDate madeOn) {
        LinkedHashMap<String, List<List<Object>>> data = new LinkedHashMap<>();
        data.put("SHEET", rows("SELECT " + String.join(", ", sheetColumns()) + " FROM sheet WHERE id = ?", sheetId));
        for (Table t : tables()) {
            data.put(t.name(), rows("SELECT " + String.join(", ", t.columns()) + " FROM " + t.name() + " WHERE "
                    + t.where(), sheetId));
        }
        jdbc.update("INSERT INTO sheet_snapshot (sheet_id, made_on, data) VALUES (?, ?, ?)", sheetId, madeOn,
                write(data));
    }

    /** The rows a query returns, the first the column names. */
    private List<List<Object>> rows(String sql, String sheetId) {
        return jdbc.query(sql, (ResultSet rs) -> {
            ResultSetMetaData meta = rs.getMetaData();
            List<List<Object>> rows = new ArrayList<>();
            List<Object> names = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                names.add(meta.getColumnName(i));
            }
            rows.add(names);
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        }, sheetId);
    }

    /**
     * The sheet's columns that can be set (all but its id and generated ones), less those that have no
     * history: its parent is shown as of a date as it is now.
     */
    private List<String> sheetColumns() {
        if (sheetColumns == null) {
            sheetColumns = columns("SHEET").stream().filter(c -> !c.equals("ID") && !UNKEPT_SHEET_COLUMNS.contains(c))
                    .toList();
        }
        return sheetColumns;
    }

    /** The kept tables, those others refer to first. */
    private List<Table> tables() {
        if (tables != null) {
            return tables;
        }
        List<String> names = jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME", String.class);
        List<Table> found = new ArrayList<>();
        for (String parent : PARENTS) {
            found.add(new Table(parent, "sheet_id = ?", columns(parent)));
        }
        for (String name : names) {
            if (SKIPPED.contains(name) || PARENTS.contains(name)) {
                continue;
            }
            List<String> columns = columns(name);
            // a period's rows go by the period they refer to, also those naming a person since merged away
            String where = columns.contains("SHEET_ID") ? "sheet_id = ?"
                    : columns.contains("PERIOD_ID") ? "period_id IN (SELECT id FROM period WHERE sheet_id = ?)"
                    : columns.contains("PERSON_ID") ? "person_id IN (SELECT id FROM person WHERE sheet_id = ?)"
                    : columns.contains("CLAIM_ID") ? "claim_id IN (SELECT id FROM claim WHERE sheet_id = ?)"
                    : columns.contains("PLAN_ID") ? "plan_id IN (SELECT id FROM plan WHERE sheet_id = ?)"
                    : null;
            if (where != null) {
                found.add(new Table(name, where, columns));
            }
        }
        tables = List.copyOf(found);
        return tables;
    }

    /** A table's columns that are stored (not generated), in order. */
    private List<String> columns(String table) {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC'"
                + " AND TABLE_NAME = ? AND IS_GENERATED = 'NEVER' ORDER BY ORDINAL_POSITION", String.class, table);
    }

    private static byte[] write(LinkedHashMap<String, List<List<Object>>> data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<List<Object>>> read(byte[] data) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (Map<String, List<List<Object>>>) in.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
