package net.officefloor.hq.app.history;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.settings.SheetSetting;
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
        Map<String, String> latest = new LinkedHashMap<>();
        jdbc.query("SELECT entity, entity_id, state FROM sheet_history WHERE sheet_id = ? AND made_on <= ? ORDER BY id",
                rs -> {
                    latest.put(rs.getString(1) + ":" + rs.getString(2), rs.getString(3));
                }, sheetId, Date.valueOf(date));
        Sheet sheet = null;
        List<Person> people = new ArrayList<>();
        List<Claim> claims = new ArrayList<>();
        List<Rate> rates = new ArrayList<>();
        List<SheetSetting> settings = new ArrayList<>();
        for (Map.Entry<String, String> row : latest.entrySet()) {
            String state = row.getValue();
            if (state == null) continue;
            switch (row.getKey().substring(0, row.getKey().indexOf(':'))) {
                case "sheet" -> sheet = read(state, Sheet.class);
                case "person" -> people.add(read(state, Person.class));
                case "claim" -> claims.add(read(state, Claim.class));
                case "rate" -> rates.add(read(state, Rate.class));
                case "setting" -> settings.add(read(state, SheetSetting.class));
                default -> {
                }
            }
        }
        if (sheet == null) {
            // The sheet was started on a later date than its today was then set to: it stood as started.
            sheet = jdbc.query("SELECT state FROM sheet_history WHERE sheet_id = ? AND entity = 'sheet' ORDER BY id",
                    rs -> rs.next() ? read(rs.getString(1), Sheet.class) : null, sheetId);
        }
        return new SheetAsOf(date, sheet == null ? null : sheet.getPolicyDate(), people, claims, rates, settings);
    }

    private static <T> T read(String state, Class<T> type) {
        return HistoryJson.MAPPER.readValue(state, type);
    }
}
