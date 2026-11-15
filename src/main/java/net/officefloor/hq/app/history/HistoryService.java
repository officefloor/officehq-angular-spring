package net.officefloor.hq.app.history;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sheet's history: each version of its claims, people, rates and dated settings a change left,
 * with the sheet's today when the change was made. A version is written (by {@link HistoryListener},
 * in the change's own transaction, so a refused change writes none) only when it differs from the
 * one before it. {@link #asOf} rebuilds the sheet as it stood at the end of a date.
 */
@Service
public class HistoryService {

    private static final String SHEET = "sheet";
    private static final String PERSON = "person";
    private static final String RATE = "rate";
    private static final String CLAIM = "claim";

    record SettingState(String key, LocalDate from, String value) {
    }

    record SheetState(LocalDate policyDate, List<SettingState> settings) {
    }

    record GradeState(LocalDate from, String grade) {
    }

    record HoldState(LocalDate from, LocalDate to) {
    }

    record PersonState(String name, String grade, List<GradeState> grades, Integer terms, LocalDate retiredFrom,
            List<HoldState> holds) {
    }

    record RateState(String grade, BigDecimal pct, LocalDate from, LocalDate to) {
    }

    record ClaimState(long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, ClaimStatus status,
            String number, BigDecimal settledDue, boolean stated, BigDecimal statedDue, String corrects,
            LocalDate deferredUntil) {
    }

    /** The sheet as it stood at the end of a date: its settings (its today that date), its people
     *  and claims by id, and its rates; none of it is stored. */
    public record AsOf(Sheet sheet, Map<Long, Person> people, List<Rate> rates, Map<Long, Claim> claims) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public HistoryService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Write the version of the sheet's settings now, if it differs from the last one. */
    public void recordSheet(Sheet sheet) {
        List<SettingState> settings = sheet.getDatedSettings().values().stream()
                .flatMap(List::stream)
                .map(d -> new SettingState(d.getKey(), d.getFrom(), d.getValue()))
                .toList();
        write(sheet.getId(), SHEET, sheet.getId(), new SheetState(sheet.getPolicyDate(), settings));
    }

    /** Write the version of an entity of the sheet now, if it differs from the last one. */
    void record(Object entity) {
        if (entity instanceof Claim c) {
            write(c.getSheetId(), CLAIM, String.valueOf(c.getId()), new ClaimState(c.getPersonId(), c.getAmount(),
                    c.getDate(), c.getDueBy(), c.getStatus(), c.getNumber(), c.getSettledDue(), c.isStated(),
                    c.getStatedDue(), c.getCorrects(), c.getDeferredUntil()));
        } else if (entity instanceof Person p) {
            List<Person.DatedGrade> all = p.getGrades();
            write(p.getSheetId(), PERSON, String.valueOf(p.getId()), new PersonState(p.getName(), all.get(0).grade(),
                    all.stream().skip(1).map(g -> new GradeState(g.from(), g.grade())).toList(), p.getTerms(),
                    p.getRetiredFrom(), p.getHolds().stream().map(h -> new HoldState(h.from(), h.to())).toList()));
        } else if (entity instanceof Rate r) {
            write(r.getSheetId(), RATE, String.valueOf(r.getId()),
                    new RateState(r.getGrade(), r.getPct(), r.getFrom(), r.getTo()));
        } else if (entity instanceof Sheet s) {
            recordSheet(s);
        }
    }

    private void write(String sheetId, String kind, String entityId, Object state) {
        String text = json.writeValueAsString(state);
        List<String> last = jdbc.queryForList(
                "SELECT state FROM history WHERE sheet_id = ? AND kind = ? AND entity_id = ? ORDER BY id DESC LIMIT 1",
                String.class, sheetId, kind, entityId);
        if (!last.isEmpty() && last.get(0).equals(text)) {
            return;
        }
        List<Date> today = jdbc.queryForList("SELECT today FROM sheet WHERE id = ?", Date.class, sheetId);
        LocalDate madeOn = today.isEmpty() ? LocalDate.now() : today.get(0).toLocalDate();
        jdbc.update("INSERT INTO history (sheet_id, kind, entity_id, made_on, state) VALUES (?, ?, ?, ?, ?)",
                sheetId, kind, entityId, Date.valueOf(madeOn), text);
    }

    /** The sheet as it stood at the end of {@code on}: each claim, person and rate, and its
     *  settings, in the last version a change made while the sheet's today was on or before it left
     *  (one with none was not yet there), and {@code on} as its today. */
    public AsOf asOf(String sheetId, LocalDate on) {
        Map<String, String> latest = new LinkedHashMap<>();
        jdbc.query("SELECT kind, entity_id, made_on, state FROM history WHERE sheet_id = ? ORDER BY id",
                rs -> {
                    if (!rs.getDate("made_on").toLocalDate().isAfter(on)) {
                        latest.put(rs.getString("kind") + "/" + rs.getString("entity_id"), rs.getString("state"));
                    }
                }, sheetId);
        Sheet sheet = new Sheet(sheetId, "", on);
        Map<Long, Person> people = new LinkedHashMap<>();
        List<Rate> rates = new ArrayList<>();
        Map<Long, Claim> claims = new LinkedHashMap<>();
        latest.forEach((key, state) -> {
            String kind = key.substring(0, key.indexOf('/'));
            String id = key.substring(key.indexOf('/') + 1);
            switch (kind) {
                case SHEET -> {
                    SheetState s = json.readValue(state, SheetState.class);
                    sheet.setPolicyDate(s.policyDate());
                    s.settings().forEach(d -> sheet.recordSetting(d.key(), d.from(), d.value()));
                }
                case PERSON -> people.put(Long.valueOf(id), personOf(sheetId, json.readValue(state, PersonState.class)));
                case RATE -> {
                    RateState r = json.readValue(state, RateState.class);
                    rates.add(new Rate(sheetId, r.grade(), r.from(), r.to(), r.pct()));
                }
                case CLAIM -> claims.put(Long.valueOf(id), claimOf(sheetId, json.readValue(state, ClaimState.class)));
                default -> {
                }
            }
        });
        rates.sort(Comparator.comparing(Rate::getGrade));
        return new AsOf(sheet, people, rates, claims);
    }

    private static Person personOf(String sheetId, PersonState s) {
        Person p = new Person(sheetId, s.name(), s.grade());
        s.grades().forEach(g -> p.recordGrade(g.grade(), g.from()));
        p.setTerms(s.terms());
        p.setRetiredFrom(s.retiredFrom());
        s.holds().forEach(h -> p.addHold(h.from(), h.to()));
        return p;
    }

    private static Claim claimOf(String sheetId, ClaimState s) {
        Claim c = new Claim(sheetId, s.personId(), s.amount(), s.date(), s.dueBy(), "");
        c.setStatus(s.status());
        c.setNumber(s.number());
        c.setSettledDue(s.settledDue());
        c.setCorrects(s.corrects());
        c.setDeferredUntil(s.deferredUntil());
        if (s.stated()) {
            c.bind(s.statedDue());
        }
        return c;
    }
}
