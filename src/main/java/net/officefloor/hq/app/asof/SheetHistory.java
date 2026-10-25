package net.officefloor.hq.app.asof;

import jakarta.servlet.ServletException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The history of each sheet, for the as-of view. Every commit of a change on a sheet keeps all the
 * sheet's rows, in every table that belongs to it, against the today the change was made on: the
 * sheet's today when the request making it began, so a change of the today itself (and the periods
 * it closes) is kept against the day being left (see {@link HistoryTransactionManager}); a later
 * change on the same today replaces what was kept for it.
 * The sheet as it stood at the end of a date is what the last change made on a today on or before
 * that date left, and {@link #restore} puts the sheet back to it inside a transaction that is then
 * rolled back.
 *
 * <p>The tables that belong to a sheet are found from the schema: those with a {@code sheet_id}
 * column, and those whose rows reference the rows of such a table, so a table added later is kept
 * with no change here.
 */
@Service
public class SheetHistory {

    /** A body run while a request changes a sheet. */
    @FunctionalInterface
    public interface Body {
        void run() throws IOException, ServletException;
    }

    /** The sheet the current request changes, if it changes one. */
    private static final ThreadLocal<Changing> CHANGING = new ThreadLocal<>();

    private static final String SHEET = "SHEET";
    private static final Set<String> NOT_KEPT = Set.of("SHEET_HISTORY", "FLYWAY_SCHEMA_HISTORY");

    /** The rows of one table, as kept. */
    private record Rows(String table, String[] columns, List<Object[]> rows) implements Serializable {
    }

    /** A sheet a request changes, and the sheet's today when the request began; none for a new sheet. */
    private record Changing(String sheet, LocalDate madeOn) {
    }

    /** A table that belongs to a sheet: the condition that picks a sheet's rows from it. */
    private record Table(String name, List<String> columns, String ofSheet) {
    }

    private final JdbcTemplate jdbc;
    private volatile List<Table> tables;

    public SheetHistory(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Run the body as a change of the sheet: each commit it makes is kept in the sheet's history,
     *  against the sheet's today as the body begins. */
    public void changing(String sheet, Body body) throws IOException, ServletException {
        Changing was = CHANGING.get();
        CHANGING.set(new Changing(sheet, todayOf(sheet)));
        try {
            body.run();
        } finally {
            if (was == null) CHANGING.remove();
            else CHANGING.set(was);
        }
    }

    /** The sheet the current request changes, if any. */
    static Optional<String> changingSheet() {
        return Optional.ofNullable(CHANGING.get()).map(Changing::sheet);
    }

    /** Keep what the sheet holds now against the today the change was made on; within the transaction
     *  about to commit. A sheet the request made is kept against its today now. */
    public void keep(String sheet) {
        LocalDate now = todayOf(sheet);
        if (now == null) return;
        Changing changing = CHANGING.get();
        LocalDate madeOn = changing != null && changing.sheet().equals(sheet) && changing.madeOn() != null
                ? changing.madeOn() : now;
        byte[] data = serialize(capture(sheet));
        List<byte[]> last = jdbc.queryForList(
                "SELECT rows_data FROM sheet_history WHERE sheet_id = ? ORDER BY seq DESC LIMIT 1", byte[].class, sheet);
        if (!last.isEmpty() && Arrays.equals(last.get(0), data)) return;
        jdbc.update("MERGE INTO sheet_history (sheet_id, today, seq, rows_data) KEY (sheet_id, today) "
                + "VALUES (?, ?, NEXT VALUE FOR sheet_history_seq, ?)", sheet, madeOn, data);
    }

    private LocalDate todayOf(String sheet) {
        List<LocalDate> today = jdbc.queryForList("SELECT today FROM sheet WHERE id = ?", LocalDate.class, sheet);
        return today.isEmpty() ? null : today.get(0);
    }

    /**
     * Put the sheet back to how it stood at the end of the date, with the date as its today: what the
     * last change made on a today on or before it left, or an empty sheet when there was none. Only
     * for a transaction that is rolled back afterwards.
     */
    public void restore(String sheet, LocalDate asOf) {
        List<byte[]> kept = jdbc.queryForList(
                "SELECT rows_data FROM sheet_history WHERE sheet_id = ? AND today <= ? ORDER BY seq DESC LIMIT 1",
                byte[].class, sheet, asOf);
        List<Table> all = tables();
        for (int i = all.size() - 1; i >= 0; i--) {
            Table table = all.get(i);
            jdbc.update("DELETE FROM " + table.name() + " WHERE " + table.ofSheet(), sheet);
        }
        if (kept.isEmpty()) {
            jdbc.update("INSERT INTO sheet (id, created_at, today) VALUES (?, CURRENT_TIMESTAMP, ?)", sheet, asOf);
            return;
        }
        Map<String, Rows> rows = deserialize(kept.get(0)).stream()
                .collect(Collectors.toMap(Rows::table, (r) -> r));
        for (Table table : all) {
            Rows these = rows.get(table.name());
            if (these == null || these.rows().isEmpty()) continue;
            String insert = "INSERT INTO " + table.name() + " (" + String.join(", ", these.columns()) + ") VALUES ("
                    + String.join(", ", java.util.Collections.nCopies(these.columns().length, "?")) + ")";
            jdbc.batchUpdate(insert, these.rows());
        }
        if (jdbc.update("UPDATE sheet SET today = ? WHERE id = ?", asOf, sheet) == 0) {
            jdbc.update("INSERT INTO sheet (id, created_at, today) VALUES (?, CURRENT_TIMESTAMP, ?)", sheet, asOf);
        }
    }

    private List<Rows> capture(String sheet) {
        List<Rows> all = new ArrayList<>();
        for (Table table : tables()) {
            String columns = String.join(", ", table.columns());
            String order = java.util.stream.IntStream.rangeClosed(1, table.columns().size())
                    .mapToObj(Integer::toString).collect(Collectors.joining(", "));
            List<Object[]> rows = jdbc.query("SELECT " + columns + " FROM " + table.name() + " WHERE "
                    + table.ofSheet() + " ORDER BY " + order, (rs, n) -> {
                        Object[] row = new Object[table.columns().size()];
                        for (int c = 0; c < row.length; c++) row[c] = rs.getObject(c + 1);
                        return row;
                    }, sheet);
            all.add(new Rows(table.name(), table.columns().toArray(String[]::new), rows));
        }
        return all;
    }

    /** The tables that belong to a sheet, each after the tables its rows reference. */
    private List<Table> tables() {
        List<Table> found = tables;
        if (found == null) {
            found = jdbc.execute((java.sql.Connection connection) -> discover(connection.getMetaData()));
            tables = found;
        }
        return found;
    }

    private static List<Table> discover(DatabaseMetaData meta) throws SQLException {
        String schema = "PUBLIC";
        Map<String, List<String>> columns = new LinkedHashMap<>();
        try (ResultSet rs = meta.getTables(null, schema, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME").toUpperCase();
                if (!NOT_KEPT.contains(name)) columns.put(name, new ArrayList<>());
            }
        }
        for (Map.Entry<String, List<String>> table : columns.entrySet()) {
            try (ResultSet rs = meta.getColumns(null, schema, table.getKey(), "%")) {
                List<String[]> found = new ArrayList<>();
                while (rs.next()) {
                    if ("YES".equalsIgnoreCase(rs.getString("IS_GENERATEDCOLUMN"))) continue;
                    found.add(new String[] {rs.getString("ORDINAL_POSITION"), rs.getString("COLUMN_NAME").toUpperCase()});
                }
                found.sort((a, b) -> Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0])));
                found.forEach((c) -> table.getValue().add(c[1]));
            }
        }
        // Each table's references: column -> [referenced table, referenced column].
        Map<String, List<String[]>> references = new HashMap<>();
        for (String table : columns.keySet()) {
            List<String[]> refs = new ArrayList<>();
            try (ResultSet rs = meta.getImportedKeys(null, schema, table)) {
                while (rs.next()) {
                    refs.add(new String[] {rs.getString("FKCOLUMN_NAME").toUpperCase(),
                            rs.getString("PKTABLE_NAME").toUpperCase(), rs.getString("PKCOLUMN_NAME").toUpperCase()});
                }
            }
            references.put(table, refs);
        }
        Map<String, String> ofSheet = new HashMap<>();
        Set<String> ordered = new LinkedHashSet<>();
        for (String table : columns.keySet()) place(table, columns, references, ofSheet, ordered, new LinkedHashSet<>());
        List<Table> result = new ArrayList<>();
        for (String table : ordered) {
            if (ofSheet.get(table) != null) result.add(new Table(table, columns.get(table), ofSheet.get(table)));
        }
        return List.copyOf(result);
    }

    /** Place a table after the tables it references, and work out how its rows belong to a sheet. */
    private static void place(String table, Map<String, List<String>> columns, Map<String, List<String[]>> references,
            Map<String, String> ofSheet, Set<String> ordered, Set<String> visiting) {
        if (ordered.contains(table) || !columns.containsKey(table) || !visiting.add(table)) return;
        for (String[] ref : references.getOrDefault(table, List.of())) {
            if (!ref[1].equals(table)) place(ref[1], columns, references, ofSheet, ordered, visiting);
        }
        String condition = null;
        if (table.equals(SHEET)) {
            condition = "ID = ?";
        } else if (columns.get(table).contains("SHEET_ID")) {
            condition = "SHEET_ID = ?";
        } else {
            for (String[] ref : references.getOrDefault(table, List.of())) {
                String parent = ofSheet.get(ref[1]);
                if (parent != null && !ref[1].equals(table)) {
                    condition = ref[0] + " IN (SELECT " + ref[2] + " FROM " + ref[1] + " WHERE " + parent + ")";
                    break;
                }
            }
        }
        ofSheet.put(table, condition);
        ordered.add(table);
    }

    private static byte[] serialize(List<Rows> rows) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new ArrayList<>(rows));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static List<Rows> deserialize(byte[] data) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (List<Rows>) in.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
