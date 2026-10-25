package net.officefloor.hq.app.sheet;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.asof.SheetHistory;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Clones a sheet to another id; a steward's change. */
@Service
public class CloneService {

    /** The tables copied row for row: each with the columns copied, in the order their rows were made. */
    private static final Map<String, String> COPIED = new java.util.LinkedHashMap<>();

    static {
        COPIED.put("sheet_setting", "setting_key, setting_value");
        COPIED.put("sheet_setting_dated", "setting_key, from_date, setting_value");
        COPIED.put("setting_change", "setting_key, from_date, setting_value, made_on");
        COPIED.put("sheet_role", "user_name, role");
        COPIED.put("rate", "grade, pct, from_date, to_date");
        COPIED.put("rate_change", "grade, from_date, to_date, pct, made_on");
        COPIED.put("holiday", "holiday_date");
        COPIED.put("void_reason", "reason");
        COPIED.put("claim_template", "name, amount, note");
        COPIED.put("sheet_tag", "name");
        // Every period comes across open.
        COPIED.put("period", "name, name_key, from_date, to_date");
    }

    private final SheetRepository sheets;
    private final SettingService settings;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final SheetHistory history;
    private final Rollup rollup;
    private final Audit audit;
    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entities;

    public CloneService(SheetRepository sheets, SettingService settings, PersonRepository people,
            ClaimRepository claims, ClaimService claimService, SheetHistory history, Rollup rollup, Audit audit,
            DataSource dataSource) {
        this.sheets = sheets;
        this.settings = settings;
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.history = history;
        this.rollup = rollup;
        this.audit = audit;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Clone the sheet to another id, which must never have been used. The clone gets the sheet's today,
     * settings, roles, rates, holidays, void reasons, templates, people (with everything they have), their
     * patrons, the pools, the periods (all open) and the claims (status, frozen figures, dates, notes, logs),
     * whose numbers are given afresh by the clone's own numbering in the order they were opened (by the
     * sheet's today when each was opened, then by the order they were added); statements are not copied.
     * Refused unless the user is a steward of the sheet, and when the other id is this sheet's or was used.
     */
    @Transactional
    public void cloneTo(String sheetId, String user, String toSheetId) {
        Sheet from = sheets.findById(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can clone the sheet.");
        }
        if (toSheetId.equals(sheetId) || sheets.existsById(toSheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The other sheet has already been used.");
        }
        try {
            sheets.saveAndFlush(new Sheet(toSheetId, Instant.now(), from.getToday()));
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The other sheet has already been used.");
        }

        COPIED.forEach((table, columns) -> jdbc.update("INSERT INTO " + table + " (sheet_id, " + columns + ") SELECT ?, "
                + columns + " FROM " + table + " WHERE sheet_id = ?" + (hasId(table) ? " ORDER BY id" : ""),
                toSheetId, sheetId));

        // The people, in the order they were added, then in their places in the list.
        List<Person> theirs = people.findInListOrder(sheetId);
        Map<Long, Person> clones = new HashMap<>();
        for (Person person : theirs.stream().sorted(Comparator.comparing(Person::getId)).toList()) {
            clones.put(person.getId(), people.save(person.cloneTo(toSheetId)));
        }
        people.flush();
        List<Long> places = clones.values().stream().map(Person::getId).sorted().toList();
        for (int i = 0; i < theirs.size(); i++) clones.get(theirs.get(i).getId()).placeAt(places.get(i));
        people.flush();
        Map<Long, Long> personIds = new HashMap<>();
        clones.forEach((id, clone) -> personIds.put(id, clone.getId()));

        for (Map<String, Object> patron : jdbc.queryForList(
                "SELECT person_id, patron_id FROM person_patron WHERE sheet_id = ? ORDER BY id", sheetId)) {
            jdbc.update("INSERT INTO person_patron (sheet_id, person_id, patron_id) VALUES (?, ?, ?)", toSheetId,
                    personIds.get(id(patron, "PERSON_ID")), personIds.get(id(patron, "PATRON_ID")));
        }

        Map<Long, Long> poolIds = new HashMap<>();
        for (Map<String, Object> pool : jdbc.queryForList("SELECT id, name FROM pool WHERE sheet_id = ? ORDER BY id",
                sheetId)) {
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update((connection) -> {
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO pool (sheet_id, name) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
                insert.setString(1, toSheetId);
                insert.setString(2, (String) pool.get("NAME"));
                return insert;
            }, key);
            poolIds.put(id(pool, "ID"), key.getKey().longValue());
        }
        for (Map<String, Object> member : jdbc.queryForList("SELECT m.pool_id, m.person_id, m.member_pool_id, m.share, "
                + "m.from_date, m.to_date FROM pool_member m JOIN pool p ON p.id = m.pool_id WHERE p.sheet_id = ? "
                + "ORDER BY m.id", sheetId)) {
            jdbc.update("INSERT INTO pool_member (pool_id, person_id, member_pool_id, share, from_date, to_date) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", poolIds.get(id(member, "POOL_ID")),
                    personIds.get(id(member, "PERSON_ID")), poolIds.get(id(member, "MEMBER_POOL_ID")),
                    member.get("SHARE"), member.get("FROM_DATE"), member.get("TO_DATE"));
        }

        // The claims that had a number take the clone's, in the order they were opened.
        List<Claim> all = claims.findBySheetIdOrderByIdDesc(sheetId);
        Map<String, String> numbers = new HashMap<>();
        all.stream().filter(c -> c.getNumber() != null)
                .sorted(Comparator.comparing(Claim::openedOn, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Claim::getPersonId).thenComparing(Claim::getId))
                .forEach(c -> numbers.put(c.getNumber(), claimService.nextNumber(toSheetId, c)));
        List<Claim> oldestFirst = new ArrayList<>(all);
        Collections.reverse(oldestFirst);
        for (Claim claim : oldestFirst) {
            Claim copy = claim.copyTo(toSheetId, personIds.get(claim.getPersonId()), claim.getListed(),
                    numbers.get(claim.getNumber()),
                    claim.getCorrects() == null ? null : numbers.get(claim.getCorrects()),
                    claim.getSplitFrom() == null ? null : numbers.get(claim.getSplitFrom()));
            copy.setRef(claim.getRef());
            claims.save(copy);
        }
        claims.flush();
        entities.flush();

        audit.recordOnCommit("SHEET_CLONE_MADE sheet=" + sheetId + " by=" + user + " to=" + toSheetId);
        audit.recordOnCommit("SHEET_CLONED sheet=" + toSheetId + " by=" + user + " from=" + sheetId);
        rollup.recordAll(user, toSheetId);
        // The request changes this sheet, which is kept as it commits; the clone is kept here.
        history.keep(toSheetId);
    }

    private static boolean hasId(String table) {
        return !table.startsWith("sheet_setting") && !table.equals("sheet_role");
    }

    private static Long id(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : ((Number) value).longValue();
    }
}
