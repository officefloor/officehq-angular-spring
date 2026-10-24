package net.officefloor.hq.app.history;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads a sheet as it stood at the end of a date from its history (see {@link HistoryListener}): every
 * change made while the sheet's today was on or before that date counts, every later change does not. */
@Service
public class SheetHistory {

    private final JdbcTemplate jdbc;

    public SheetHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The sheet as it stood at the end of {@code date}, that date standing in for its today. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SheetAsOf asOf(String sheetId, LocalDate date) {
        return asOf(sheetId, date, new LinkedHashSet<>(List.of(sheetId)));
    }

    /** The sheet as it stood at the end of {@code date}, with its parents' rates as they stood then;
     * {@code chain} holds the sheet and the sheets below it whose parents are being read. */
    private SheetAsOf asOf(String sheetId, LocalDate date, Set<String> chain) {
        Map<String, String> latest = new LinkedHashMap<>();
        jdbc.query("SELECT entity, entity_id, state FROM sheet_history WHERE sheet_id = ? AND made_on <= ? ORDER BY id",
                rs -> {
                    latest.put(rs.getString(1) + ":" + rs.getString(2), rs.getString(3));
                }, sheetId, Date.valueOf(date));
        Map<String, Class<?>> types = new HashMap<>();
        HistoryListener.KEPT.forEach((type, name) -> types.put(name, type));
        Sheet sheet = null;
        Map<Class<?>, List<Object>> rows = new HashMap<>();
        for (Map.Entry<String, String> row : latest.entrySet()) {
            String state = row.getValue();
            if (state == null) continue;
            Class<?> type = types.get(row.getKey().substring(0, row.getKey().indexOf(':')));
            if (type == null) continue;
            Object entity = read(state, type);
            if (entity instanceof Sheet stood) sheet = stood;
            else rows.computeIfAbsent(type, t -> new ArrayList<>()).add(entity);
        }
        if (sheet == null) {
            // The sheet was started on a later date than its today was then set to: it stood as started.
            sheet = jdbc.query("SELECT state FROM sheet_history WHERE sheet_id = ? AND entity = 'sheet' ORDER BY id",
                    rs -> rs.next() ? read(rs.getString(1), Sheet.class) : null, sheetId);
        }
        List<List<Rate>> inherited = new ArrayList<>();
        String parentId = sheet == null ? null : sheet.getParentId();
        if (parentId != null && chain.size() <= RateChain.LEVELS_UP && !chain.contains(parentId)) {
            Set<String> up = new LinkedHashSet<>(chain);
            up.add(parentId);
            inherited.addAll(asOf(parentId, date, up).rateLevels());
        }
        return new SheetAsOf(date, sheet, rows, inherited);
    }

    /** The ids of the sheets naming {@code parentId} as their parent as they stood at the end of
     * {@code date} (each as {@link #asOf} reads it: as started when it was started on a later date),
     * by id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> childrenAsOf(String parentId, LocalDate date) {
        String literal = "\"parentId\":" + HistoryJson.MAPPER.writeValueAsString(parentId);
        String named = "%" + literal.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<String> candidates = jdbc.queryForList("SELECT DISTINCT sheet_id FROM sheet_history"
                + " WHERE entity = 'sheet' AND state LIKE ? ESCAPE '\\' ORDER BY sheet_id", String.class, named);
        List<String> children = new ArrayList<>();
        for (String sheetId : candidates) {
            List<String> stood = jdbc.query("SELECT state FROM sheet_history WHERE sheet_id = ? AND entity = 'sheet'"
                    + " AND made_on <= ? ORDER BY id DESC LIMIT 1", (rs, n) -> rs.getString(1), sheetId, Date.valueOf(date));
            if (stood.isEmpty()) {
                // The sheet was started on a later date than its today was then set to: it stood as started.
                stood = jdbc.query("SELECT state FROM sheet_history WHERE sheet_id = ? AND entity = 'sheet' ORDER BY id LIMIT 1",
                        (rs, n) -> rs.getString(1), sheetId);
            }
            String state = stood.isEmpty() ? null : stood.get(0);
            Sheet sheet = state == null ? null : read(state, Sheet.class);
            if (sheet != null && parentId.equals(sheet.getParentId())) children.add(sheetId);
        }
        return children;
    }

    /** The sheet as it stood at the end of {@code date} (see {@link #asOf}), read on its own. */
    @Transactional(readOnly = true)
    public SheetAsOf read(String sheetId, LocalDate date) {
        return asOf(sheetId, date);
    }

    private static <T> T read(String state, Class<T> type) {
        return HistoryJson.MAPPER.readValue(state, type);
    }
}
