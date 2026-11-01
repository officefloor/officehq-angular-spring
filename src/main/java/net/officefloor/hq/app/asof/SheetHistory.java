package net.officefloor.hq.app.asof;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each sheet held after each change to it, so it can be shown as it stood at the end of a date.
 * A snapshot is every row the sheet owns in every table (the sheet's own row, the rows carrying its
 * id, and the rows hanging off those by a reference), with the sheet's today once the change was
 * made. Any table added later is covered the same way, without listing it here.
 */
@Service
public class SheetHistory {

    private static final String ROOT = "SHEET";
    /** Tables that are not the sheet's data: the schema's history and this history itself. */
    private static final Set<String> NOT_SHEET_DATA = Set.of("FLYWAY_SCHEMA_HISTORY", "SHEET_SNAPSHOT");

    /** A table the sheet owns rows of: its columns and the condition (one parameter, the sheet id) selecting them. */
    private record Owned(String name, List<String> columns, String owns) {}

    /** One table's rows in a snapshot. */
    private record TableRows(String name, String[] columns, List<Object[]> rows) implements Serializable {}

    private final JdbcTemplate jdbc;
    /** The owned tables, each after every table it references. */
    private volatile List<Owned> tables;

    public SheetHistory(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Whether the sheet has been started. */
    @Transactional(readOnly = true)
    public boolean exists(String sheetId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sheet WHERE id = ?", Integer.class, sheetId);
        return n != null && n > 0;
    }

    /**
     * Record what the sheet holds now, with its today, unless that is what it held after its last
     * change.
     */
    @Transactional
    public void capture(String sheetId) {
        List<Date> today = jdbc.queryForList("SELECT today FROM sheet WHERE id = ?", Date.class, sheetId);
        if (today.isEmpty()) return;
        byte[] data = serialize(read(sheetId));
        List<byte[]> last = jdbc.query("SELECT data FROM sheet_snapshot WHERE sheet_id = ? ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getBytes(1), sheetId);
        if (!last.isEmpty() && Arrays.equals(last.get(0), data)) return;
        // the sheet as it was started stands for every date before its first change
        Date takenOn = last.isEmpty() ? null : today.get(0);
        jdbc.update("INSERT INTO sheet_snapshot (sheet_id, taken_on, data) VALUES (?, ?, ?)", sheetId, takenOn, data);
    }

    /**
     * Put the sheet back, in the current transaction, as it stood at the end of the date: as the last
     * change made while its today was on or before the date left it (as it was started, when there was
     * none), with the date as its today. The caller rolls the transaction back once it has read it.
     */
    @Transactional
    public void restore(String sheetId, LocalDate asOf) {
        List<byte[]> found = jdbc.query("SELECT data FROM sheet_snapshot WHERE sheet_id = ? AND taken_on <= ?"
                + " ORDER BY id DESC LIMIT 1", (rs, i) -> rs.getBytes(1), sheetId, Date.valueOf(asOf));
        if (found.isEmpty()) {
            found = jdbc.query("SELECT data FROM sheet_snapshot WHERE sheet_id = ? ORDER BY id LIMIT 1",
                    (rs, i) -> rs.getBytes(1), sheetId);
        }
        if (!found.isEmpty()) write(sheetId, deserialize(found.get(0)));
        jdbc.update("UPDATE sheet SET today = ? WHERE id = ?", Date.valueOf(asOf), sheetId);
    }

    private List<TableRows> read(String sheetId) {
        List<TableRows> snapshot = new ArrayList<>();
        for (Owned t : tables()) {
            String cols = String.join(", ", t.columns().stream().map(SheetHistory::quote).toList());
            StringBuilder order = new StringBuilder();
            for (int i = 1; i <= t.columns().size(); i++) order.append(i == 1 ? "" : ", ").append(i);
            List<Object[]> rows = jdbc.query("SELECT " + cols + " FROM " + quote(t.name()) + " WHERE " + t.owns()
                    + " ORDER BY " + order, (rs, i) -> {
                        Object[] row = new Object[t.columns().size()];
                        for (int c = 0; c < row.length; c++) row[c] = rs.getObject(c + 1);
                        return row;
                    }, sheetId);
            snapshot.add(new TableRows(t.name(), t.columns().toArray(String[]::new), rows));
        }
        return snapshot;
    }

    private void write(String sheetId, List<TableRows> snapshot) {
        Map<String, TableRows> byName = new HashMap<>();
        for (TableRows t : snapshot) byName.put(t.name(), t);
        List<Owned> owned = tables();
        for (int i = owned.size() - 1; i >= 0; i--) {
            Owned t = owned.get(i);
            if (!t.name().equals(ROOT)) jdbc.update("DELETE FROM " + quote(t.name()) + " WHERE " + t.owns(), sheetId);
        }
        for (Owned t : owned) {
            TableRows rows = byName.get(t.name());
            if (rows == null || rows.rows().isEmpty()) continue;
            List<String> cols = Arrays.stream(rows.columns()).filter(t.columns()::contains).toList();
            int[] at = cols.stream().mapToInt(c -> Arrays.asList(rows.columns()).indexOf(c)).toArray();
            if (t.name().equals(ROOT)) {
                List<String> set = cols.stream().filter(c -> !c.equals("ID")).toList();
                String sql = "UPDATE " + quote(ROOT) + " SET "
                        + String.join(", ", set.stream().map(c -> quote(c) + " = ?").toList()) + " WHERE ID = ?";
                for (Object[] row : rows.rows()) {
                    List<Object> args = new ArrayList<>();
                    for (int k = 0; k < cols.size(); k++) if (!cols.get(k).equals("ID")) args.add(row[at[k]]);
                    args.add(sheetId);
                    jdbc.update(sql, args.toArray());
                }
            } else {
                String sql = "INSERT INTO " + quote(t.name()) + " ("
                        + String.join(", ", cols.stream().map(SheetHistory::quote).toList()) + ") VALUES ("
                        + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
                List<Object[]> args = new ArrayList<>();
                for (Object[] row : rows.rows()) {
                    Object[] values = new Object[at.length];
                    for (int k = 0; k < at.length; k++) values[k] = row[at[k]];
                    args.add(values);
                }
                jdbc.batchUpdate(sql, args);
            }
        }
    }

    /** The tables the sheets own rows of, read from the schema once. */
    private List<Owned> tables() {
        List<Owned> found = tables;
        if (found == null) {
            found = jdbc.execute((ConnectionCallback<List<Owned>>) con -> discover(con.getMetaData()));
            tables = found;
        }
        return found;
    }

    private static List<Owned> discover(DatabaseMetaData meta) throws java.sql.SQLException {
        String schema = "PUBLIC";
        Map<String, List<String>> columns = new TreeMap<>();
        try (ResultSet rs = meta.getTables(null, schema, "%", null)) {
            while (rs.next()) {
                String type = rs.getString("TABLE_TYPE");
                String name = rs.getString("TABLE_NAME");
                if (type != null && type.contains("TABLE") && !NOT_SHEET_DATA.contains(name)) columns.put(name, new ArrayList<>());
            }
        }
        for (Map.Entry<String, List<String>> e : columns.entrySet()) {
            try (ResultSet rs = meta.getColumns(null, schema, e.getKey(), "%")) {
                Map<Integer, String> ordered = new TreeMap<>();
                while (rs.next()) ordered.put(rs.getInt("ORDINAL_POSITION"), rs.getString("COLUMN_NAME"));
                e.getValue().addAll(ordered.values());
            }
        }
        // each table's references: referencing column -> referenced table and column
        Map<String, Map<String, String[]>> refs = new HashMap<>();
        for (String table : columns.keySet()) {
            Map<String, String[]> to = new LinkedHashMap<>();
            try (ResultSet rs = meta.getImportedKeys(null, schema, table)) {
                while (rs.next()) {
                    String parent = rs.getString("PKTABLE_NAME");
                    if (!parent.equals(table) && columns.containsKey(parent)) {
                        to.put(rs.getString("FKCOLUMN_NAME"), new String[] {parent, rs.getString("PKCOLUMN_NAME")});
                    }
                }
            }
            refs.put(table, to);
        }
        // parents first
        List<String> order = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        Set<String> left = new TreeSet<>(columns.keySet());
        while (!left.isEmpty()) {
            String next = left.stream()
                    .filter(t -> refs.get(t).values().stream().allMatch(r -> placed.contains(r[0])))
                    .findFirst().orElse(left.iterator().next());
            order.add(next);
            placed.add(next);
            left.remove(next);
        }
        Map<String, String> owns = new HashMap<>();
        List<Owned> owned = new ArrayList<>();
        for (String table : order) {
            List<String> cols = columns.get(table);
            String cond = null;
            if (table.equals(ROOT)) {
                cond = "ID = ?";
            } else if (cols.contains("SHEET_ID")) {
                cond = "SHEET_ID = ?";
            } else {
                for (Map.Entry<String, String[]> r : refs.get(table).entrySet()) {
                    String parent = owns.get(r.getValue()[0]);
                    if (parent != null) {
                        cond = quote(r.getKey()) + " IN (SELECT " + quote(r.getValue()[1]) + " FROM "
                                + quote(r.getValue()[0]) + " WHERE " + parent + ")";
                        break;
                    }
                }
            }
            if (cond == null) continue;
            owns.put(table, cond);
            owned.add(new Owned(table, List.copyOf(cols), cond));
        }
        return List.copyOf(owned);
    }

    private static String quote(String name) {
        return "\"" + name + "\"";
    }

    private static byte[] serialize(List<TableRows> snapshot) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new ArrayList<>(snapshot));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static List<TableRows> deserialize(byte[] data) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (List<TableRows>) in.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
