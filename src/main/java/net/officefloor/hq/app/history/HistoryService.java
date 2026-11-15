package net.officefloor.hq.app.history;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonPatron;
import net.officefloor.hq.app.person.PersonStatement;
import net.officefloor.hq.app.person.PersonWatch;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.settings.Permission;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SheetDelegation;
import net.officefloor.hq.app.settings.SheetPermission;
import net.officefloor.hq.app.settings.SheetRole;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sheet's history: each version of its claims, people, rates, settings, periods, statements,
 * patrons, watches, roles and delegations a change left, with the sheet's today when the change was
 * made (one removed leaves an empty version). A version is written (by {@link HistoryListener}, in
 * the change's own transaction, so a refused change writes none) only when it differs from the one
 * before it. {@link #asOf} rebuilds the sheet as it stood at the end of a date.
 */
@Service
public class HistoryService {

    private static final String SHEET = "sheet";
    private static final String PERSON = "person";
    private static final String RATE = "rate";
    private static final String CLAIM = "claim";
    private static final String PERIOD = "period";
    private static final String STATEMENT = "statement";
    private static final String PATRON = "patron";
    private static final String WATCH = "watch";
    private static final String ROLE = "role";
    private static final String DELEGATION = "delegation";
    private static final String PERMISSION = "permission";

    /** The version an entity that was removed leaves. */
    private static final String REMOVED = "null";

    record SettingState(String key, LocalDate from, String value) {
    }

    record SheetState(LocalDate policyDate, List<SettingState> settings, LocalDate periodNumberingFrom,
            Integer graceDays, boolean archived, LocalDate entryDate, BigDecimal pot, Integer fiscalStartMonth,
            BigDecimal alertThreshold) {
    }

    record GradeState(LocalDate from, String grade) {
    }

    record HoldState(LocalDate from, LocalDate to) {
    }

    record PersonState(String name, String grade, List<GradeState> grades, Integer terms, LocalDate retiredFrom,
            List<HoldState> holds, String contact, List<String> aliases, Long position,
            String originSheetId, List<HoldState> retirements, String tags) {
    }

    /** A rate's state; its version is null in states written before rates were kept as versions
     *  (version 1), its earlier versions null in those written before rates could be forward-only. */
    record RateState(String grade, BigDecimal pct, LocalDate from, LocalDate to, Integer version, Long afterClaim,
            List<Rate.Earlier> earlier) {
    }

    record ClaimState(long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, ClaimStatus status,
            String number, BigDecimal settledDue, boolean stated, BigDecimal statedDue, String corrects,
            LocalDate deferredUntil, String note, String openedBy, long listed, int proofs, String splitFrom,
            boolean lateEntry, LocalDate reminded, String ref, String tags, String voidReason, String editedBy,
            Integer batch, Long added) {
    }

    record PeriodState(String name, LocalDate from, LocalDate to, boolean closed, boolean softClosed,
            Map<Long, BigDecimal> personTotals, Integer cap) {
    }

    record StatementState(long personId, BigDecimal dueTotal, int claims, LocalDate takenOn, LocalDate asOf,
            String takenBy) {
    }

    record PatronState(long personId, long patronId) {
    }

    record WatchState(long personId, String user) {
    }

    record RoleState(String user, String role) {
    }

    record DelegationState(String user, String steward, LocalDate from, LocalDate to) {
    }

    /** The sheet as it stood at the end of a date: its settings (its today that date), its people
     *  by id in list order, its claims by id, its rates, its periods by id, its statements by id (oldest first), its
     *  patrons and watches, its roles (by user name), its delegations by id and its permission
     *  matrix; none of it is stored. */
    public record AsOf(Sheet sheet, Map<Long, Person> people, List<Rate> rates, Map<Long, Claim> claims,
            Map<Long, Period> periods, Map<Long, PersonStatement> statements, List<PersonPatron> patrons,
            List<PersonWatch> watches, List<SheetRole> roles, Map<Long, SheetDelegation> delegations,
            Map<Permission, String> permissions) {
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
        write(sheet.getId(), SHEET, sheet.getId(), new SheetState(sheet.getPolicyDate(), settings,
                sheet.getPeriodNumberingFrom(), sheet.getGraceDays(), sheet.isArchived(), sheet.getEntryDate(),
                sheet.getPot(), sheet.getFiscalStartMonth(), sheet.getAlertThreshold()));
    }

    /** Write the version of an entity of the sheet now, if it differs from the last one. */
    void record(Object entity) {
        if (entity instanceof Claim c) {
            write(c.getSheetId(), CLAIM, String.valueOf(c.getId()), new ClaimState(c.getPersonId(), c.getAmount(),
                    c.getDate(), c.getDueBy(), c.getStatus(), c.getNumber(), c.getSettledDue(), c.isStated(),
                    c.getStatedDue(), c.getCorrects(), c.getDeferredUntil(), c.getNote(), c.getOpenedBy(),
                    c.getListed(), c.getProofs(), c.getSplitFrom(), c.isLateEntry(), c.getReminded(),
                    c.getRef(), c.getTags(), c.getVoidReason(), c.getEditedBy(), c.getBatch(), c.getAdded()));
        } else if (entity instanceof Person p) {
            List<Person.DatedGrade> all = p.getGrades();
            write(p.getSheetId(), PERSON, String.valueOf(p.getId()), new PersonState(p.getName(), all.get(0).grade(),
                    all.stream().skip(1).map(g -> new GradeState(g.from(), g.grade())).toList(), p.getTerms(),
                    p.getRetiredFrom(), p.getHolds().stream().map(h -> new HoldState(h.from(), h.to())).toList(),
                    p.getContact(), p.getAliases(), p.getPosition(), p.getOriginSheetId(),
                    p.getRetirements().stream().map(r -> new HoldState(r.from(), r.to())).toList(), p.getTags()));
        } else if (entity instanceof Rate r) {
            write(r.getSheetId(), RATE, String.valueOf(r.getId()),
                    new RateState(r.getGrade(), r.getPct(), r.getFrom(), r.getTo(), r.getVersion(),
                            r.getAfterClaim(), r.getEarlier()));
        } else if (entity instanceof Sheet s) {
            recordSheet(s);
        } else if (entity instanceof Period p) {
            write(p.getSheetId(), PERIOD, String.valueOf(p.getId()), new PeriodState(p.getName(), p.getFrom(), p.getTo(),
                    p.isClosed(), p.isSoftClosed(), new TreeMap<>(p.getClosedPersonTotals()), p.getCap()));
        } else if (entity instanceof PersonStatement st) {
            write(st.getSheetId(), STATEMENT, String.valueOf(st.getId()), new StatementState(st.getPersonId(),
                    st.getDueTotal(), st.getClaims(), st.getTakenOn(), st.getAsOf(), st.getTakenBy()));
        } else if (entity instanceof PersonPatron pp) {
            write(pp.getSheetId(), PATRON, String.valueOf(pp.getId()), new PatronState(pp.getPersonId(), pp.getPatronId()));
        } else if (entity instanceof PersonWatch w) {
            write(w.getSheetId(), WATCH, String.valueOf(w.getId()), new WatchState(w.getPersonId(), w.getUserName()));
        } else if (entity instanceof SheetRole r) {
            write(r.getSheetId(), ROLE, r.getUser(), new RoleState(r.getUser(), r.getRole().key()));
        } else if (entity instanceof SheetDelegation d) {
            write(d.getSheetId(), DELEGATION, String.valueOf(d.getId()),
                    new DelegationState(d.getUser(), d.getSteward(), d.getFrom(), d.getTo()));
        } else if (entity instanceof SheetPermission sp) {
            write(sp.getSheetId(), PERMISSION, sp.getAction().key(), sp.getRoles());
        }
    }

    /** Write that an entity of the sheet is gone now: a person merged or transferred away (with
     *  their claims), a statement cancelled, a watch stopped, a permission set back to its standing rule. */
    void removed(Object entity) {
        if (entity instanceof Person p) {
            writeText(p.getSheetId(), PERSON, String.valueOf(p.getId()), REMOVED);
        } else if (entity instanceof Claim c) {
            writeText(c.getSheetId(), CLAIM, String.valueOf(c.getId()), REMOVED);
        } else if (entity instanceof PersonStatement st) {
            writeText(st.getSheetId(), STATEMENT, String.valueOf(st.getId()), REMOVED);
        } else if (entity instanceof PersonPatron pp) {
            writeText(pp.getSheetId(), PATRON, String.valueOf(pp.getId()), REMOVED);
        } else if (entity instanceof PersonWatch w) {
            writeText(w.getSheetId(), WATCH, String.valueOf(w.getId()), REMOVED);
        } else if (entity instanceof SheetDelegation d) {
            writeText(d.getSheetId(), DELEGATION, String.valueOf(d.getId()), REMOVED);
        } else if (entity instanceof SheetRole r) {
            writeText(r.getSheetId(), ROLE, r.getUser(), REMOVED);
        } else if (entity instanceof SheetPermission sp) {
            writeText(sp.getSheetId(), PERMISSION, sp.getAction().key(), REMOVED);
        }
    }

    private void write(String sheetId, String kind, String entityId, Object state) {
        writeText(sheetId, kind, entityId, json.writeValueAsString(state));
    }

    private void writeText(String sheetId, String kind, String entityId, String text) {
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

    /** The sheet as it stood at the end of {@code on}: each of its entities and its settings in the
     *  last version a change made while the sheet's today was on or before it left (one with none,
     *  or removed then, was not there), and {@code on} as its today. */
    public AsOf asOf(String sheetId, LocalDate on) {
        Map<String, String> latest = new LinkedHashMap<>();
        jdbc.query("SELECT kind, entity_id, made_on, state FROM history WHERE sheet_id = ? ORDER BY id",
                rs -> {
                    if (!rs.getDate("made_on").toLocalDate().isAfter(on)) {
                        latest.put(rs.getString("kind") + "/" + rs.getString("entity_id"), rs.getString("state"));
                    }
                }, sheetId);
        Sheet sheet = new Sheet(sheetId, "", on);
        Map<Long, Person> people = new TreeMap<>();
        List<Rate> rates = new ArrayList<>();
        Map<Long, Claim> claims = new LinkedHashMap<>();
        Map<Long, Period> periods = new TreeMap<>();
        Map<Long, PersonStatement> statements = new TreeMap<>();
        List<PersonPatron> patrons = new ArrayList<>();
        List<PersonWatch> watches = new ArrayList<>();
        List<SheetRole> roles = new ArrayList<>();
        Map<Long, SheetDelegation> delegations = new TreeMap<>();
        Map<Permission, String> permissions = new EnumMap<>(Permission.class);
        latest.forEach((key, state) -> {
            if (state.equals(REMOVED)) {
                return;
            }
            String kind = key.substring(0, key.indexOf('/'));
            String id = key.substring(key.indexOf('/') + 1);
            switch (kind) {
                case SHEET -> {
                    SheetState s = json.readValue(state, SheetState.class);
                    sheet.setPolicyDate(s.policyDate());
                    s.settings().forEach(d -> sheet.recordSetting(d.key(), d.from(), d.value()));
                    sheet.setPeriodNumberingFrom(s.periodNumberingFrom());
                    sheet.setGraceDays(s.graceDays());
                    sheet.setEntryDate(s.entryDate());
                    sheet.setPot(s.pot());
                    sheet.setAlertThreshold(s.alertThreshold());
                    sheet.setFiscalStartMonth(s.fiscalStartMonth() == null ? 1 : s.fiscalStartMonth());
                    if (s.archived()) {
                        sheet.archive();
                    }
                }
                case PERSON -> people.put(Long.valueOf(id), personOf(sheetId, Long.parseLong(id), json.readValue(state, PersonState.class)));
                case RATE -> {
                    RateState r = json.readValue(state, RateState.class);
                    rates.add(new Rate(sheetId, r.grade(), r.from(), r.to(), r.pct(), r.version() == null ? 1 : r.version(),
                            r.afterClaim(), r.earlier() == null ? List.of() : r.earlier()));
                }
                case CLAIM -> claims.put(Long.valueOf(id), claimOf(sheetId, Long.parseLong(id), json.readValue(state, ClaimState.class)));
                case PERIOD -> {
                    PeriodState p = json.readValue(state, PeriodState.class);
                    Period period = new Period(sheetId, p.name(), p.from(), p.to());
                    period.setCap(p.cap());
                    if (p.closed()) {
                        period.close(p.personTotals());
                    } else if (p.softClosed()) {
                        period.softClose(p.personTotals());
                    }
                    periods.put(Long.valueOf(id), period);
                }
                case STATEMENT -> {
                    StatementState st = json.readValue(state, StatementState.class);
                    statements.put(Long.valueOf(id), new PersonStatement(sheetId, st.personId(), st.dueTotal(),
                            st.claims(), st.takenOn(), st.asOf(), st.takenBy()));
                }
                case PATRON -> {
                    PatronState pp = json.readValue(state, PatronState.class);
                    patrons.add(new PersonPatron(sheetId, pp.personId(), pp.patronId()));
                }
                case WATCH -> {
                    WatchState w = json.readValue(state, WatchState.class);
                    watches.add(new PersonWatch(sheetId, w.personId(), w.user()));
                }
                case ROLE -> {
                    RoleState r = json.readValue(state, RoleState.class);
                    roles.add(new SheetRole(sheetId, r.user(), Role.of(r.role())));
                }
                case DELEGATION -> {
                    DelegationState d = json.readValue(state, DelegationState.class);
                    delegations.put(Long.valueOf(id), new SheetDelegation(sheetId, d.user(), d.steward(), d.from(), d.to()));
                }
                case PERMISSION -> permissions.put(Permission.of(id), json.readValue(state, String.class));
                default -> {
                }
            }
        });
        rates.sort(Comparator.comparing(Rate::getGrade));
        roles.sort(Comparator.comparing(SheetRole::getUser));
        Map<Long, Claim> claimsListed = new LinkedHashMap<>();
        claims.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Long, Claim> e) -> e.getValue().getListed()).reversed())
                .forEach(e -> claimsListed.put(e.getKey(), e.getValue()));
        Map<Long, Person> peopleListed = new LinkedHashMap<>();
        people.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<Long, Person> e) -> e.getValue().getPosition())
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> peopleListed.put(e.getKey(), e.getValue()));
        return new AsOf(sheet, peopleListed, rates, claimsListed, periods, statements, patrons, watches, roles,
                delegations, permissions);
    }

    private static Person personOf(String sheetId, long id, PersonState s) {
        Person p = new Person(sheetId, s.name(), s.grade());
        s.grades().forEach(g -> p.recordGrade(g.grade(), g.from()));
        p.setTerms(s.terms());
        p.setContact(s.contact());
        p.setTags(s.tags());
        p.setRetiredFrom(s.retiredFrom());
        s.holds().forEach(h -> p.addHold(h.from(), h.to()));
        // a version written before people had positions is placed by its id, as they then were
        p.setPosition(s.position() != null ? s.position() : id);
        if (s.aliases() != null) { // a version written before people had aliases has none
            s.aliases().forEach(p::addAlias);
        }
        p.setOriginSheetId(s.originSheetId());
        if (s.retirements() != null) { // a version written before people could be unretired has none
            s.retirements().forEach(r -> p.addRetirement(r.from(), r.to()));
        }
        return p;
    }

    private static Claim claimOf(String sheetId, long id, ClaimState s) {
        Claim c = new Claim(sheetId, s.personId(), s.amount(), s.date(), s.dueBy(), "");
        // a version written before claims kept what they were added as was added as its own id
        c.setAdded(s.added() != null ? s.added() : id);
        c.setStatus(s.status());
        c.setNumber(s.number());
        c.setSettledDue(s.settledDue());
        c.setCorrects(s.corrects());
        c.setDeferredUntil(s.deferredUntil());
        c.setNote(s.note() == null ? "" : s.note());
        c.setOpenedBy(s.openedBy());
        c.setListed(s.listed());
        c.setProofs(s.proofs());
        c.setSplitFrom(s.splitFrom());
        c.setLateEntry(s.lateEntry());
        c.setReminded(s.reminded());
        c.setRef(s.ref());
        c.setTags(s.tags());
        c.setVoidReason(s.voidReason());
        c.setEditedBy(s.editedBy());
        c.setBatch(s.batch());
        if (s.stated()) {
            c.bind(s.statedDue());
        }
        return c;
    }
}
