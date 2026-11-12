package net.officefloor.hq.app.asof;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps how each sheet stood at the end of each of its todays, and puts a sheet back as it stood as of a date.
 *
 * <p>After every change to a sheet, every row the sheet keeps (the sheet's own row, the rows carrying its id, and the
 * rows hanging off its people, periods and claims) is copied under the sheet's today then. Showing the sheet as of a
 * date replaces those rows, inside a transaction the caller rolls back, with the copy of the latest today on or before
 * the date (none: an empty sheet), and the date stands in for the sheet's today. A kept row that has since moved to
 * another sheet (a transferred person's, say) is taken back for that transaction. The tables are found from the
 * schema, so a table a later change adds is kept as well.
 */
@Service
public class SheetSnapshotService {

    private static final String SHEET = "SHEET";
    private static final Set<String> NOT_KEPT = Set.of("SHEET_SNAPSHOT", "FLYWAY_SCHEMA_HISTORY");

    /** A table a sheet keeps rows in: the columns copied, which of its rows are the sheet's, and its key columns. */
    private record Table(String name, List<String> columns, String where, List<String> keys) {
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private volatile List<Table> tables;

    public SheetSnapshotService(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Whether the sheet has been started. */
    public boolean exists(String sheetId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sheet WHERE id = ?", Integer.class, sheetId);
        return n != null && n > 0;
    }

    /** Copy how the sheet stands now under its today, replacing an earlier copy of that today. */
    @Transactional
    public void capture(String sheetId) {
        List<LocalDate> today = jdbc.queryForList("SELECT today FROM sheet WHERE id = ?", LocalDate.class, sheetId);
        if (today.isEmpty()) {
            return;
        }
        LinkedHashMap<String, ArrayList<LinkedHashMap<String, Object>>> rows = new LinkedHashMap<>();
        for (Table table : tables()) {
            ArrayList<LinkedHashMap<String, Object>> list = new ArrayList<>();
            jdbc.query("SELECT " + String.join(", ", table.columns()) + " FROM " + table.name() + " WHERE "
                    + table.where(), rs -> {
                        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                        for (int i = 0; i < table.columns().size(); i++) {
                            row.put(table.columns().get(i), rs.getObject(i + 1));
                        }
                        list.add(row);
                    }, sheetId);
            rows.put(table.name(), list);
        }
        jdbc.update("MERGE INTO sheet_snapshot (sheet_id, made_on, seq, data) KEY (sheet_id, made_on) "
                + "VALUES (?, ?, NEXT VALUE FOR sheet_snapshot_seq, ?)", sheetId, today.get(0), serialize(rows));
    }

    /**
     * Put the sheet's rows back as they stood at the end of the date, with the date as its today. Only call it inside
     * a transaction that is rolled back: it changes the store for that transaction alone.
     */
    public void restore(String sheetId, LocalDate asOf) {
        List<byte[]> found = jdbc.query("SELECT data FROM sheet_snapshot WHERE sheet_id = ? AND made_on <= ? "
                + "ORDER BY seq DESC LIMIT 1", (rs, i) -> rs.getBytes(1), sheetId, asOf);
        Map<String, List<Map<String, Object>>> rows = found.isEmpty() ? Map.of() : deserialize(found.get(0));
        List<Table> ordered = tables();
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Table table = ordered.get(i);
            if (!table.name().equals(SHEET)) {
                jdbc.update("DELETE FROM " + table.name() + " WHERE " + table.where(), sheetId);
            }
        }
        for (Table table : ordered) {
            List<Map<String, Object>> kept = rows.getOrDefault(table.name(), List.of());
            if (table.name().equals(SHEET)) {
                restoreSheet(table, kept.isEmpty() ? null : kept.get(0), sheetId, asOf);
                continue;
            }
            for (Map<String, Object> row : kept) {
                List<String> columns = table.columns().stream().filter(row::containsKey).toList();
                // A row since moved to another sheet keeps its key there: take it back rather than add it again.
                boolean keyed = !table.keys().isEmpty() && columns.containsAll(table.keys());
                jdbc.update((keyed ? "MERGE INTO " : "INSERT INTO ") + table.name() + " (" + String.join(", ", columns)
                        + ")" + (keyed ? " KEY (" + String.join(", ", table.keys()) + ")" : "") + " VALUES ("
                        + String.join(", ", columns.stream().map(c -> "?").toList()) + ")",
                        columns.stream().map(row::get).toArray());
            }
        }
    }

    /**
     * The sheet's own row as it was kept, or as a sheet just started has it when nothing was kept by then. Its parent
     * sheet and the sheet it was cloned from are links between sheets, not part of how the sheet stood: they stay as
     * they are now.
     */
    private void restoreSheet(Table table, Map<String, Object> row, String sheetId, LocalDate asOf) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (String column : table.columns()) {
            if (column.equals("ID") || column.equals("CREATED_BY") || column.equals("TODAY") || column.equals("PARENT")
                    || column.equals("SOURCE")) {
                continue;
            }
            if (row == null) {
                sets.add(column + " = DEFAULT");
            } else if (row.containsKey(column)) {
                sets.add(column + " = ?");
                args.add(row.get(column));
            }
        }
        sets.add("TODAY = ?");
        args.add(asOf);
        args.add(sheetId);
        jdbc.update("UPDATE sheet SET " + String.join(", ", sets) + " WHERE id = ?", args.toArray());
    }

    /** The tables a sheet keeps rows in, each after the tables it refers to. */
    private List<Table> tables() {
        List<Table> found = tables;
        if (found == null) {
            try (Connection connection = dataSource.getConnection()) {
                found = discover(connection.getMetaData());
            } catch (SQLException e) {
                throw new IllegalStateException("The schema could not be read", e);
            }
            tables = found;
        }
        return found;
    }

    private static List<Table> discover(DatabaseMetaData meta) throws SQLException {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        // The columns that may be null, as TABLE.COLUMN.
        Set<String> nullable = new LinkedHashSet<>();
        try (ResultSet rs = meta.getTables(null, "PUBLIC", "%", new String[] { "TABLE", "BASE TABLE" })) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME").toUpperCase(Locale.ROOT);
                if (!NOT_KEPT.contains(name)) {
                    columns.put(name, new ArrayList<>());
                }
            }
        }
        for (Map.Entry<String, List<String>> e : columns.entrySet()) {
            try (ResultSet rs = meta.getColumns(null, "PUBLIC", e.getKey(), "%")) {
                while (rs.next()) {
                    if (!"YES".equalsIgnoreCase(rs.getString("IS_GENERATEDCOLUMN"))) {
                        e.getValue().add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                    }
                    if ("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"))) {
                        nullable.add(e.getKey() + "." + rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                    }
                }
            }
        }
        Map<String, List<String>> keys = new HashMap<>();
        for (String name : columns.keySet()) {
            List<String> key = new ArrayList<>();
            try (ResultSet rs = meta.getPrimaryKeys(null, "PUBLIC", name)) {
                while (rs.next()) {
                    key.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                }
            }
            keys.put(name, key);
        }
        // Each table's references to other tables: its column -> the table referred to.
        Map<String, Map<String, String>> refs = new HashMap<>();
        for (String name : columns.keySet()) {
            Map<String, String> to = new LinkedHashMap<>();
            try (ResultSet rs = meta.getImportedKeys(null, "PUBLIC", name)) {
                while (rs.next()) {
                    to.put(rs.getString("FKCOLUMN_NAME").toUpperCase(Locale.ROOT),
                            rs.getString("PKTABLE_NAME").toUpperCase(Locale.ROOT));
                }
            }
            refs.put(name, to);
        }
        Map<String, String> where = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : columns.entrySet()) {
            String name = e.getKey();
            if (name.equals(SHEET)) {
                where.put(name, "id = ?");
            } else if (e.getValue().contains("SHEET_ID")) {
                where.put(name, "sheet_id = ?");
            } else {
                // Through a reference to a table carrying the sheet's id: one that is never null, when there is one,
                // so that every row is reached.
                Map.Entry<String, String> through = null;
                for (Map.Entry<String, String> ref : refs.get(name).entrySet()) {
                    List<String> parent = columns.get(ref.getValue());
                    if (parent != null && parent.contains("SHEET_ID")) {
                        if (through == null || (nullable.contains(name + "." + through.getKey())
                                && !nullable.contains(name + "." + ref.getKey()))) {
                            through = ref;
                        }
                    }
                }
                if (through != null) {
                    where.put(name, through.getKey() + " IN (SELECT id FROM " + through.getValue() + " WHERE sheet_id = ?)");
                }
            }
        }
        // Parents before the tables referring to them.
        Set<String> ordered = new LinkedHashSet<>();
        while (ordered.size() < where.size()) {
            int before = ordered.size();
            for (String name : where.keySet()) {
                if (!ordered.contains(name) && refs.get(name).values().stream()
                        .allMatch(p -> p.equals(name) || ordered.contains(p) || !where.containsKey(p))) {
                    ordered.add(name);
                }
            }
            if (ordered.size() == before) {
                throw new IllegalStateException("The sheet's tables refer to each other in a cycle");
            }
        }
        return ordered.stream().map(n -> new Table(n, columns.get(n), where.get(n), keys.get(n))).toList();
    }

    private static byte[] serialize(Object rows) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(rows);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> deserialize(byte[] data) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (Map<String, List<Map<String, Object>>>) in.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
