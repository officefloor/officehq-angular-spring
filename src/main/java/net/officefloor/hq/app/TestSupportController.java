package net.officefloor.hq.app;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.people.Grade;
import net.officefloor.hq.app.people.Person;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-spec data setup for the end-to-end tests. Profile-guarded so it exists ONLY under the test
 * launch (bin/start sets spring.profiles.active=e2e) — never in a real deploy. Tests call these to
 * ARRANGE data; they ASSERT only through the UI.
 */
@Profile("e2e")
@RestController
@RequestMapping("/__test__")
public class TestSupportController {

    private static final Set<String> PERSON_FIELDS = Set.of("id", "name", "alpha", "beta", "gamma", "grade", "onset", "active");

    private static final Set<String> PATRON_FIELDS = Set.of("personId", "patronId");

    private static final Set<String> REGION_FIELDS = Set.of("personId", "region");

    private static final Set<String> SHEET_FIELDS = Set.of("id", "anchor", "people", "patrons", "regions");

    private final Audit audit;
    private final JdbcTemplate jdbc;

    public TestSupportController(Audit audit, JdbcTemplate jdbc) {
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /** Truncate all domain tables and clear the audit file so each spec starts clean. */
    @PostMapping("/reset")
    @Transactional
    public void reset() {
        audit.clear();
        // Person is referenced by its patrons, so delete rather than truncate.
        jdbc.update("DELETE FROM person_patron");
        jdbc.update("DELETE FROM person_region");
        jdbc.update("DELETE FROM person");
        jdbc.update("DELETE FROM sheet");
        jdbc.update("DELETE FROM unnamed_sheet");
        jdbc.execute("ALTER TABLE person ALTER COLUMN id RESTART WITH 1");
    }

    /**
     * Insert the fixture a spec needs; the payload shape evolves with the schema. Store every field
     * given, or refuse with HTTP 400 naming it — never ignore one silently (AGENTS.md §4.2).
     */
    @PostMapping("/seed")
    @Transactional
    public void seed(@RequestBody Map<String, Object> fixture) {
        for (String key : fixture.keySet()) {
            if (!key.equals("anchor") && !key.equals("people") && !key.equals("patrons") && !key.equals("regions")
                    && !key.equals("sheets")) {
                throw badRequest("unknown fixture key: " + key);
            }
        }
        // The top-level anchor, people and patrons are the unnamed sheet's.
        LocalDate anchor = date(fixture.get("anchor"), "anchor");
        if (anchor != null) {
            jdbc.update("INSERT INTO unnamed_sheet (id, anchor) VALUES (1, ?)", anchor);
        }
        // People, patrons and regions at the top level are on the unnamed sheet.
        seedSheet(null, fixture.get("people"), fixture.get("patrons"), fixture.get("regions"), "");
        for (Map<String, Object> sheet : rows(fixture.get("sheets"), "sheets")) {
            for (String field : sheet.keySet()) {
                if (!SHEET_FIELDS.contains(field)) {
                    throw badRequest("cannot store sheets." + field);
                }
            }
            if (!(sheet.get("id") instanceof String id) || id.isBlank()) {
                throw badRequest("sheets.id is required");
            }
            jdbc.update("INSERT INTO sheet (id, anchor) VALUES (?, ?)", id, date(sheet.get("anchor"), "sheets.anchor"));
            seedSheet(id, sheet.get("people"), sheet.get("patrons"), sheet.get("regions"), "sheets.");
        }
    }

    private static String sheetWhere(String sheetId) {
        return sheetId == null ? "sheet_id IS NULL" : "sheet_id = ?";
    }

    private static Object[] sheetArgs(String sheetId, Object... more) {
        List<Object> args = new ArrayList<>();
        if (sheetId != null) {
            args.add(sheetId);
        }
        args.addAll(List.of(more));
        return args.toArray();
    }

    private long nextLocalId(String sheetId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(local_id), 0) + 1 FROM person WHERE " + sheetWhere(sheetId),
                Long.class, sheetArgs(sheetId));
    }

    /** The internal key of the person with an id on a sheet; refuses an id the sheet does not have. */
    private long internalId(String sheetId, long localId, String field) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM person WHERE " + sheetWhere(sheetId) + " AND local_id = ?",
                Long.class, sheetArgs(sheetId, localId));
        if (ids.isEmpty()) {
            throw badRequest(field + " " + localId + " is not a person on the sheet");
        }
        return ids.get(0);
    }

    /** Inserts one sheet's people and then their patrons and regions; a null sheet id is the unnamed sheet. */
    private void seedSheet(String sheetId, Object peopleRows, Object patronRows, Object regionRows, String prefix) {
        for (Map<String, Object> row : rows(peopleRows, prefix + "people")) {
            for (String field : row.keySet()) {
                if (!PERSON_FIELDS.contains(field)) {
                    throw badRequest("cannot store " + prefix + "people." + field);
                }
            }
            if (!(row.get("name") instanceof String name)) {
                throw badRequest(prefix + "people.name is required");
            }
            Integer alpha = integer(row.get("alpha"), prefix + "people.alpha");
            Integer beta = integer(row.get("beta"), prefix + "people.beta");
            Integer gamma = integer(row.get("gamma"), prefix + "people.gamma");
            String grade = grade(row.get("grade"), prefix + "people.grade");
            LocalDate onset = date(row.get("onset"), prefix + "people.onset");
            Boolean active = bool(row.get("active"), prefix + "people.active");
            // A person's id is their id within the sheet; the internal key is generated.
            Integer localId = integer(row.get("id"), prefix + "people.id");
            jdbc.update("INSERT INTO person (sheet_id, local_id, name, alpha, beta, gamma, grade, onset, active) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    sheetId, localId != null ? localId : nextLocalId(sheetId), name, alpha, beta, gamma, grade, onset,
                    active != null ? active : true);
        }
        // Patrons link people by id, so add them once every person exists.
        for (Map<String, Object> row : rows(patronRows, prefix + "patrons")) {
            for (String field : row.keySet()) {
                if (!PATRON_FIELDS.contains(field)) {
                    throw badRequest("cannot store " + prefix + "patrons." + field);
                }
            }
            Integer personId = integer(row.get("personId"), prefix + "patrons.personId");
            Integer patronId = integer(row.get("patronId"), prefix + "patrons.patronId");
            if (personId == null || patronId == null) {
                throw badRequest(prefix + "patrons rows need personId and patronId");
            }
            jdbc.update("INSERT INTO person_patron (person_id, patron_id) VALUES (?, ?)",
                    internalId(sheetId, personId, prefix + "patrons.personId"),
                    internalId(sheetId, patronId, prefix + "patrons.patronId"));
        }
        // Regions link a person by id to each region they belong to.
        for (Map<String, Object> row : rows(regionRows, prefix + "regions")) {
            for (String field : row.keySet()) {
                if (!REGION_FIELDS.contains(field)) {
                    throw badRequest("cannot store " + prefix + "regions." + field);
                }
            }
            Integer personId = integer(row.get("personId"), prefix + "regions.personId");
            String region = text(row.get("region"), prefix + "regions.region");
            if (personId == null || region == null) {
                throw badRequest(prefix + "regions rows need personId and region");
            }
            jdbc.update("INSERT INTO person_region (person_id, region) VALUES (?, ?)",
                    internalId(sheetId, personId, prefix + "regions.personId"), region);
        }
    }

    /**
     * Read back what is stored, in the same shape and with the same field names the fixtures use,
     * so earlier checkpoints' test data can be replayed and checked (AGENTS.md §4.3, §4.4). The
     * unnamed sheet's people are at the top level; each named sheet nests its own.
     */
    @GetMapping("/fixture")
    public Map<String, Object> fixture() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String anchor : jdbc.queryForList("SELECT CAST(anchor AS VARCHAR) FROM unnamed_sheet WHERE anchor IS NOT NULL",
                String.class)) {
            out.put("anchor", anchor);
        }
        out.putAll(sheetContents(null));
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT id, CAST(anchor AS VARCHAR) AS anchor FROM sheet ORDER BY id")) {
            Map<String, Object> sheet = new LinkedHashMap<>();
            sheet.put("id", row.get("id"));
            if (row.get("anchor") != null) {
                sheet.put("anchor", row.get("anchor"));
            }
            String id = (String) row.get("id");
            sheet.putAll(sheetContents(id));
            sheets.add(sheet);
        }
        out.put("sheets", sheets);
        return out;
    }

    private Map<String, Object> sheetContents(String sheetId) {
        String where = sheetId == null ? "p.sheet_id IS NULL" : "p.sheet_id = ?";
        Object[] args = sheetId == null ? new Object[0] : new Object[] {sheetId};
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("people", jdbc.queryForList("SELECT local_id AS \"id\", name AS \"name\", alpha AS \"alpha\", "
                + "beta AS \"beta\", gamma AS \"gamma\", grade AS \"grade\", "
                + "CAST(onset AS VARCHAR) AS \"onset\", active AS \"active\" FROM person p WHERE " + where + " ORDER BY local_id", args));
        out.put("patrons", jdbc.queryForList("SELECT p.local_id AS \"personId\", o.local_id AS \"patronId\" "
                + "FROM person_patron pp JOIN person p ON p.id = pp.person_id JOIN person o ON o.id = pp.patron_id "
                + "WHERE " + where + " ORDER BY p.local_id, o.local_id", args));
        out.put("regions", jdbc.queryForList("SELECT p.local_id AS \"personId\", pr.region AS \"region\" "
                + "FROM person_region pr JOIN person p ON p.id = pr.person_id "
                + "WHERE " + where + " ORDER BY p.local_id, pr.region", args));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value, String key) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(r -> r instanceof Map)) {
            throw badRequest(key + " must be a list of rows");
        }
        return (List<Map<String, Object>>) list;
    }

    private static Integer integer(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        if (value instanceof String s && s.matches("-?\\d+")) {
            return Integer.valueOf(s);
        }
        throw badRequest(field + " must be a whole number, got " + value);
    }

    private static String grade(Object value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Grade.parse(String.valueOf(value)).name();
        } catch (IllegalArgumentException e) {
            throw badRequest(field + ": " + e.getMessage());
        }
    }

    private static String text(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw badRequest(field + " must be text, got " + value);
        }
        if (s.length() > Person.REGION_MAX_LENGTH) {
            throw badRequest(field + " must be at most " + Person.REGION_MAX_LENGTH + " characters, got " + s);
        }
        return s;
    }

    private static Boolean bool(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && (s.equals("true") || s.equals("false"))) {
            return Boolean.valueOf(s);
        }
        throw badRequest(field + " must be true or false, got " + value);
    }

    private static LocalDate date(Object value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            throw badRequest(field + " must be a date as yyyy-mm-dd, got " + value);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
