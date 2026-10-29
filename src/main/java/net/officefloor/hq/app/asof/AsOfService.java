package net.officefloor.hq.app.asof;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import net.officefloor.hq.app.rate.RateChain;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps every state each sheet stands in, and answers for a sheet as it stood at the end of a day.
 *
 * After each change to a sheet, every row kept for it is stored, with the sheet's today then (see
 * {@link #capture}). The rows kept for a sheet are found from the schema itself: those of the sheet, of each table
 * with a sheet_id, and of each table that refers to rows so kept; so a table added later is kept with no more ado.
 *
 * A sheet as of a day is a store of its own (see {@link #storeAsOf}): the schema, holding the last state stored
 * while the sheet's today was on or before the day (an empty sheet when there is none), with the day as its today;
 * and the same for each of its parent sheets whose rates it can take, each as it stood at the end of the day by its own
 * today (as a statement as of the day takes their rates), linked as they are now.
 * The whole app reads it unchanged, so every figure on the screen is as of the day.
 */
@Service
public class AsOfService {

    /** The tables that keep no sheet's data. */
    private static final Set<String> NOT_KEPT = Set.of("FLYWAY_SCHEMA_HISTORY", "SHEET_SNAPSHOT");

    /** How many stores as of a day are kept at once, each for a sheet's state and a day. */
    private static final int STORES_KEPT = 32;

    /** A table whose rows are kept for a sheet: the stored columns, and the rows' filter by the sheet's id. */
    private record Kept(String table, List<String> columns, String where) {
    }

    /** A store as of a day, kept alive by its open connection. */
    private record Store(DataSource dataSource, Connection holder) {
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final SheetSnapshotRepository snapshots;
    private final TransactionTemplate readOnly;
    private final AtomicLong storeNames = new AtomicLong();
    private final Map<String, Store> stores = new LinkedHashMap<>(16, 0.75f, true);
    private volatile List<Kept> kept;
    private volatile List<String> schema;

    public AsOfService(DataSource dataSource, SheetSnapshotRepository snapshots,
            PlatformTransactionManager transactions) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.snapshots = snapshots;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Reads the sheet as it stood at the end of the day (see {@link #storeAsOf}): the read runs in a transaction of its
     * own on that store, so it sees nothing read from the store itself or from another sheet as of a day. To be called
     * while the request in hand reads the store itself.
     */
    public <T> T readAsOf(String sheetId, LocalDate day, Supplier<T> read) {
        if (AsOfContext.active()) {
            throw new IllegalStateException("Already reading a sheet as of a date");
        }
        AsOfContext.enter(storeAsOf(sheetId, day));
        try {
            return readOnly.execute(status -> read.get());
        } finally {
            AsOfContext.leave();
        }
    }

    /** Stores the sheet's state as it stands now, with its today, unless it is the state last stored. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void capture(String sheetId) {
        List<LocalDate> today = jdbc.queryForList("SELECT today FROM sheet WHERE id = ?", LocalDate.class, sheetId);
        if (today.isEmpty()) {
            return;
        }
        List<SheetState.Table> tables = new ArrayList<>();
        for (Kept k : kept()) {
            String columns = k.columns().stream().map(AsOfService::quoted).collect(Collectors.joining(", "));
            String order = IntStream.rangeClosed(1, k.columns().size()).mapToObj(Integer::toString)
                    .collect(Collectors.joining(", "));
            List<Object[]> rows = jdbc.query(
                    "SELECT " + columns + " FROM " + quoted(k.table()) + " WHERE " + k.where() + " ORDER BY " + order,
                    (rs, n) -> {
                        Object[] row = new Object[k.columns().size()];
                        for (int i = 0; i < row.length; i++) {
                            row[i] = rs.getObject(i + 1);
                        }
                        return row;
                    }, sheetId);
            tables.add(new SheetState.Table(k.table(), k.columns(), rows));
        }
        byte[] data = new SheetState(tables).toBytes();
        String digest = SheetState.digest(data);
        if (snapshots.latestDigests(sheetId, Limit.of(1)).contains(digest)) {
            return;
        }
        snapshots.saveAndFlush(new SheetSnapshot(sheetId, today.get(0), digest, data));
    }

    /**
     * The store holding the sheet as it stood at the end of the day: the last state stored while its today was on or
     * before the day (an empty sheet with none), with the day standing in for its today; each parent sheet as it was
     * last stored while its own today was on or before the day, else as it was when the sheet's own state was stored
     * (an empty sheet with neither). Only to be read.
     */
    @Transactional(readOnly = true)
    public DataSource storeAsOf(String sheetId, LocalDate day) {
        Map<String, String> lineage = lineage(sheetId);
        List<Long> ids = new ArrayList<>();
        for (String id : lineage.keySet()) {
            // a parent keeps its own today: it stands as last stored while that was on or before the day; with none
            // so stored, it stands as it was when the sheet's own state was stored
            List<Long> made = snapshots.idsMadeBy(id, day, Limit.of(1));
            if (made.isEmpty() && !ids.isEmpty() && ids.get(0) != null) {
                made = snapshots.idsStoredBy(id, ids.get(0), Limit.of(1));
            }
            ids.add(made.isEmpty() ? null : made.get(0));
        }
        String key = lineage + "|" + ids + "|" + day;
        synchronized (stores) {
            Store store = stores.get(key);
            if (store == null) {
                List<SheetState> states = new ArrayList<>();
                for (Long id : ids) {
                    states.add(id == null ? null : SheetState.of(snapshots.findById(id).orElseThrow().getData()));
                }
                store = open(lineage, day, states);
                stores.put(key, store);
                if (stores.size() > STORES_KEPT) {
                    String eldest = stores.keySet().iterator().next();
                    close(stores.remove(eldest));
                }
            }
            return store.dataSource();
        }
    }

    /**
     * The sheet's id, then its parent's, and so on as far as its rates can be taken from (see {@link RateChain}), each
     * with its parent's id as it is now (null for none).
     */
    private Map<String, String> lineage(String sheetId) {
        Map<String, String> lineage = new LinkedHashMap<>();
        String at = sheetId;
        while (at != null && !lineage.containsKey(at) && lineage.size() <= RateChain.LEVELS_UP) {
            List<String> parent = jdbc.queryForList("SELECT parent_id FROM sheet WHERE id = ?", String.class, at);
            String next = parent.isEmpty() ? null : parent.get(0);
            lineage.put(at, next);
            at = next;
        }
        return lineage;
    }

    /**
     * Opens a store of its own with the schema and, for each sheet of the lineage, its state's rows (an empty sheet
     * with none) and its parent as it is now; the day stands in for the first one's today.
     */
    private Store open(Map<String, String> lineage, LocalDate day, List<SheetState> states) {
        String sheetId = lineage.keySet().iterator().next();
        String url = "jdbc:h2:mem:asof-" + storeNames.incrementAndGet();
        try {
            Connection holder = DriverManager.getConnection(url, "sa", "");
            try (Statement st = holder.createStatement()) {
                for (String sql : schema()) {
                    st.execute(sql);
                }
                // the store closes once its last connection does
                st.execute("SET DB_CLOSE_DELAY 0");
                // the rows are as they stood together, in whatever order; they are read, never changed
                st.execute("SET REFERENTIAL_INTEGRITY FALSE");
            }
            for (SheetState state : states) {
                if (state == null) {
                    continue;
                }
                for (SheetState.Table t : state.tables()) {
                    if (t.rows().isEmpty()) {
                        continue;
                    }
                    String sql = "INSERT INTO " + quoted(t.name()) + " ("
                            + t.columns().stream().map(AsOfService::quoted).collect(Collectors.joining(", "))
                            + ") VALUES (" + t.columns().stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
                    try (PreparedStatement insert = holder.prepareStatement(sql)) {
                        for (Object[] row : t.rows()) {
                            for (int i = 0; i < row.length; i++) {
                                insert.setObject(i + 1, row[i]);
                            }
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                }
            }
            try (PreparedStatement today = holder.prepareStatement("UPDATE sheet SET today = ? WHERE id = ?")) {
                today.setObject(1, day);
                today.setString(2, sheetId);
                today.executeUpdate();
            }
            for (Map.Entry<String, String> link : lineage.entrySet()) {
                try (PreparedStatement parent = holder.prepareStatement(
                        "UPDATE sheet SET parent_id = ? WHERE id = ?")) {
                    parent.setString(1, link.getValue());
                    parent.setString(2, link.getKey());
                    if (parent.executeUpdate() == 0) {
                        try (PreparedStatement empty = holder.prepareStatement("INSERT INTO sheet"
                                + " (id, created_at, today, parent_id) VALUES (?, CURRENT_TIMESTAMP, ?, ?)")) {
                            empty.setString(1, link.getKey());
                            empty.setObject(2, day);
                            empty.setString(3, link.getValue());
                            empty.executeUpdate();
                        }
                    }
                }
            }
            return new Store(new DriverManagerDataSource(url + ";IFEXISTS=TRUE", "sa", ""), holder);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open the sheet as of " + day, e);
        }
    }

    private static void close(Store store) {
        try {
            store.holder().close();
        } catch (SQLException e) {
            // already gone
        }
    }

    /** The schema, as statements that create it empty. */
    private List<String> schema() {
        List<String> s = schema;
        if (s == null) {
            s = List.copyOf(jdbc.queryForList("SCRIPT NODATA", String.class));
            schema = s;
        }
        return s;
    }

    /** The tables whose rows are kept for a sheet, found from the schema. */
    private List<Kept> kept() {
        List<Kept> k = kept;
        if (k == null) {
            k = jdbc.execute(AsOfService::findKept);
            kept = k;
        }
        return k;
    }

    private static List<Kept> findKept(Connection c) throws SQLException {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, Boolean> hasSheetId = new LinkedHashMap<>();
        try (PreparedStatement q = c.prepareStatement(
                "SELECT c.TABLE_NAME, c.COLUMN_NAME, c.IS_GENERATED FROM INFORMATION_SCHEMA.COLUMNS c"
                        + " JOIN INFORMATION_SCHEMA.TABLES t ON t.TABLE_SCHEMA = c.TABLE_SCHEMA"
                        + " AND t.TABLE_NAME = c.TABLE_NAME"
                        + " WHERE c.TABLE_SCHEMA = 'PUBLIC' AND t.TABLE_TYPE = 'BASE TABLE'"
                        + " ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION");
                ResultSet rs = q.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (NOT_KEPT.contains(table)) {
                    continue;
                }
                columns.computeIfAbsent(table, t -> new ArrayList<>());
                if ("SHEET_ID".equals(rs.getString(2))) {
                    hasSheetId.put(table, true);
                }
                if (!"ALWAYS".equals(rs.getString(3))) {
                    columns.get(table).add(rs.getString(2));
                }
            }
        }
        Map<String, String> where = new LinkedHashMap<>();
        for (String table : columns.keySet()) {
            if (table.equals("SHEET")) {
                where.put(table, quoted("ID") + " = ?");
            } else if (hasSheetId.getOrDefault(table, false)) {
                where.put(table, quoted("SHEET_ID") + " = ?");
            }
        }
        // a table with no sheet_id is kept through a row it refers to that is kept
        DatabaseMetaData meta = c.getMetaData();
        boolean found = true;
        while (found) {
            found = false;
            for (String table : columns.keySet()) {
                if (where.containsKey(table)) {
                    continue;
                }
                try (ResultSet keys = meta.getImportedKeys(null, "PUBLIC", table)) {
                    while (keys.next()) {
                        String parent = keys.getString("PKTABLE_NAME");
                        if (where.containsKey(parent)) {
                            where.put(table, quoted(keys.getString("FKCOLUMN_NAME")) + " IN (SELECT "
                                    + quoted(keys.getString("PKCOLUMN_NAME")) + " FROM " + quoted(parent) + " WHERE "
                                    + where.get(parent) + ")");
                            found = true;
                            break;
                        }
                    }
                }
            }
        }
        List<Kept> kept = new ArrayList<>();
        where.forEach((table, w) -> kept.add(new Kept(table, List.copyOf(columns.get(table)), w)));
        return List.copyOf(kept);
    }

    private static String quoted(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }
}
