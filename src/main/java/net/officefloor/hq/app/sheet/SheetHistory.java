package net.officefloor.hq.app.sheet;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A sheet's history: the whole sheet as it stood after each change, kept with the sheet's today then (see
 * V51__sheet_snapshot.sql), and the sheet put back as it stood at the end of a date. Every table that holds a
 * sheet's data is kept: those with a sheet id, and those whose rows belong to the sheet's people, claims or periods.
 */
@Service
public class SheetHistory {

    /** One table of a sheet's data: its stored columns, and which of its rows are the sheet's. */
    private record Table(String name, List<String> columns, String rows) {}

    /** A table that keeps rows of the sheet's, by a column naming the owning table's row. */
    private static final Map<String, String> OWNERS = Map.of("PERSON_ID", "PERSON", "CLAIM_ID", "CLAIM",
            "PERIOD_ID", "PERIOD");

    private static final Set<String> SKIPPED = Set.of("SHEET_SNAPSHOT", "flyway_schema_history",
            "FLYWAY_SCHEMA_HISTORY");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    /** The sheet's tables, each after those it refers to; read from the schema once. */
    private volatile List<Table> tables;

    public SheetHistory(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Whether a sheet with this id is stored. */
    public boolean exists(String sheetId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sheet WHERE id = ?", Integer.class, sheetId) > 0;
    }

    /** Keep the sheet as it stands before its first change, unless something of it is kept already. */
    public void keepFirst(String sheetId) {
        if (exists(sheetId) && jdbc.queryForObject("SELECT COUNT(*) FROM sheet_snapshot WHERE sheet_id = ?",
                Integer.class, sheetId) == 0) {
            jdbc.update("INSERT INTO sheet_snapshot (sheet_id, made_on, data) VALUES (?, NULL, ?)", sheetId,
                    write(read(sheetId)));
        }
    }

    /** Keep the sheet as it stands now, with its today. */
    public void keep(String sheetId) {
        if (!exists(sheetId)) {
            return;
        }
        LocalDate today = jdbc.queryForObject("SELECT today FROM sheet WHERE id = ?", LocalDate.class, sheetId);
        byte[] data = write(read(sheetId));
        List<byte[]> last = jdbc.queryForList("SELECT data FROM sheet_snapshot WHERE sheet_id = ? AND made_on = ?"
                + " AND id = (SELECT MAX(id) FROM sheet_snapshot WHERE sheet_id = ?)", byte[].class, sheetId, today,
                sheetId);
        if (last.isEmpty() || !Arrays.equals(last.get(0), data)) {
            jdbc.update("INSERT INTO sheet_snapshot (sheet_id, made_on, data) VALUES (?, ?, ?)", sheetId, today, data);
        }
    }

    /**
     * Put the sheet back as it stood at the end of a date, with that date as its today: as kept after the last change
     * made while its today was on or before it, or as before its first change when there is none. A sheet with
     * nothing kept is left as it is. Only for a transaction that is rolled back.
     */
    public void putBack(String sheetId, LocalDate asOf) {
        if (!exists(sheetId)) {
            return;
        }
        List<byte[]> kept = jdbc.queryForList("SELECT data FROM sheet_snapshot WHERE sheet_id = ? AND made_on <= ?"
                + " ORDER BY id DESC LIMIT 1", byte[].class, sheetId, asOf);
        if (kept.isEmpty()) {
            kept = jdbc.queryForList("SELECT data FROM sheet_snapshot WHERE sheet_id = ? AND made_on IS NULL"
                    + " ORDER BY id LIMIT 1", byte[].class, sheetId);
        }
        if (!kept.isEmpty()) {
            restore(sheetId, readBack(kept.get(0)));
        }
        jdbc.update("UPDATE sheet SET today = ? WHERE id = ?", asOf, sheetId);
    }

    private void restore(String sheetId, Map<String, List<Map<String, String>>> data) {
        List<Table> all = tables();
        for (int i = all.size() - 1; i >= 0; i--) {
            Table t = all.get(i);
            if (!t.name().equals("SHEET")) {
                jdbc.update("DELETE FROM " + t.name() + " WHERE " + t.rows(), sheetId);
            }
        }
        for (Table t : all) {
            List<Map<String, String>> rows = data.getOrDefault(t.name(), List.of());
            if (t.name().equals("SHEET")) {
                for (Map<String, String> row : rows) {
                    List<String> columns = t.columns().stream().filter(c -> !c.equals("ID")).toList();
                    List<Object> args = new ArrayList<>();
                    columns.forEach(c -> args.add(row.get(c)));
                    args.add(sheetId);
                    jdbc.update("UPDATE SHEET SET " + String.join(" = ?, ", columns) + " = ? WHERE ID = ?",
                            args.toArray());
                }
                continue;
            }
            String sql = "INSERT INTO " + t.name() + " (" + String.join(", ", t.columns()) + ") VALUES ("
                    + String.join(", ", Collections.nCopies(t.columns().size(), "?")) + ")";
            for (Map<String, String> row : rows) {
                jdbc.update(sql, t.columns().stream().map(row::get).toArray());
            }
        }
    }

    private Map<String, List<Map<String, String>>> read(String sheetId) {
        Map<String, List<Map<String, String>>> data = new LinkedHashMap<>();
        for (Table t : tables()) {
            data.put(t.name(), jdbc.query("SELECT " + String.join(", ", t.columns()) + " FROM " + t.name()
                    + " WHERE " + t.rows(), (rs, n) -> {
                        Map<String, String> row = new HashMap<>();
                        for (String c : t.columns()) {
                            row.put(c, rs.getString(c));
                        }
                        return row;
                    }, sheetId));
        }
        return data;
    }

    private List<Table> tables() {
        List<Table> found = tables;
        if (found == null) {
            try (Connection connection = dataSource.getConnection()) {
                found = tablesOf(connection);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            tables = found;
        }
        return found;
    }

    private List<Table> tablesOf(Connection connection) throws SQLException {
        Map<String, Table> byName = new LinkedHashMap<>();
        for (String name : jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME", String.class)) {
            if (SKIPPED.contains(name)) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            Set<String> all = new LinkedHashSet<>();
            jdbc.query("SELECT COLUMN_NAME, IS_GENERATED FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC'"
                    + " AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION", (ResultSet rs) -> {
                        all.add(rs.getString(1));
                        if (!"ALWAYS".equals(rs.getString(2))) {
                            columns.add(rs.getString(1));
                        }
                    }, name);
            String rows = null;
            if (name.equals("SHEET")) {
                rows = "ID = ?";
            } else if (all.contains("SHEET_ID")) {
                rows = "SHEET_ID = ?";
            } else {
                for (Map.Entry<String, String> owner : OWNERS.entrySet()) {
                    if (all.contains(owner.getKey())) {
                        rows = owner.getKey() + " IN (SELECT ID FROM " + owner.getValue() + " WHERE SHEET_ID = ?)";
                        break;
                    }
                }
            }
            if (rows != null) {
                byName.put(name, new Table(name, columns, rows));
            }
        }
        // Each table after those it refers to, so rows go back in and come out in an order the keys allow.
        DatabaseMetaData meta = connection.getMetaData();
        Map<String, Set<String>> refersTo = new HashMap<>();
        for (String name : byName.keySet()) {
            Set<String> parents = new LinkedHashSet<>();
            try (ResultSet rs = meta.getImportedKeys(null, "PUBLIC", name)) {
                while (rs.next()) {
                    String parent = rs.getString("PKTABLE_NAME");
                    if (!parent.equals(name) && byName.containsKey(parent)) {
                        parents.add(parent);
                    }
                }
            }
            refersTo.put(name, parents);
        }
        List<Table> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        while (placed.size() < byName.size()) {
            boolean moved = false;
            for (String name : byName.keySet()) {
                if (!placed.contains(name) && placed.containsAll(refersTo.get(name))) {
                    placed.add(name);
                    ordered.add(byName.get(name));
                    moved = true;
                }
            }
            if (!moved) {
                throw new IllegalStateException("The sheet's tables refer to each other in a cycle.");
            }
        }
        return List.copyOf(ordered);
    }

    private static byte[] write(Map<String, List<Map<String, String>>> data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new LinkedHashMap<>(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, String>>> readBack(byte[] data) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (Map<String, List<Map<String, String>>>) in.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
