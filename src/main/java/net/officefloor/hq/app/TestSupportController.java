package net.officefloor.hq.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final Set<String> PERSON_FIELDS = Set.of("id", "name", "alpha", "beta", "gamma");

    private static final Set<String> PATRON_FIELDS = Set.of("personId", "patronId");

    private static final Set<String> SHEET_FIELDS = Set.of("id", "people", "patrons");

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
        jdbc.update("DELETE FROM person");
        jdbc.update("DELETE FROM sheet");
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
            if (!key.equals("people") && !key.equals("patrons") && !key.equals("sheets")) {
                throw badRequest("unknown fixture key: " + key);
            }
        }
        // People and patrons at the top level are on the unnamed sheet.
        seedSheet(null, fixture.get("people"), fixture.get("patrons"), "");
        for (Map<String, Object> sheet : rows(fixture.get("sheets"), "sheets")) {
            for (String field : sheet.keySet()) {
                if (!SHEET_FIELDS.contains(field)) {
                    throw badRequest("cannot store sheets." + field);
                }
            }
            if (!(sheet.get("id") instanceof String id) || id.isBlank()) {
                throw badRequest("sheets.id is required");
            }
            jdbc.update("INSERT INTO sheet (id) VALUES (?)", id);
            seedSheet(id, sheet.get("people"), sheet.get("patrons"), "sheets.");
        }
        // Keep generated ids clear of the seeded ones.
        Long next = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM person", Long.class);
        jdbc.execute("ALTER TABLE person ALTER COLUMN id RESTART WITH " + next);
    }

    /** Inserts one sheet's people and then their patrons; a null sheet id is the unnamed sheet. */
    private void seedSheet(String sheetId, Object peopleRows, Object patronRows, String prefix) {
        for (Map<String, Object> row : rows(peopleRows, prefix + "people")) {
            for (String field : row.keySet()) {
                if (!PERSON_FIELDS.contains(field)) {
                    throw badRequest("cannot store " + prefix + "people." + field);
                }
            }
            if (!(row.get("name") instanceof String name)) {
                throw badRequest(prefix + "people.name is required");
            }
            Object id = row.get("id");
            Integer alpha = integer(row.get("alpha"), prefix + "people.alpha");
            Integer beta = integer(row.get("beta"), prefix + "people.beta");
            Integer gamma = integer(row.get("gamma"), prefix + "people.gamma");
            if (id == null) {
                jdbc.update("INSERT INTO person (sheet_id, name, alpha, beta, gamma) VALUES (?, ?, ?, ?, ?)",
                        sheetId, name, alpha, beta, gamma);
            } else {
                jdbc.update("INSERT INTO person (id, sheet_id, name, alpha, beta, gamma) VALUES (?, ?, ?, ?, ?, ?)",
                        integer(id, prefix + "people.id"), sheetId, name, alpha, beta, gamma);
            }
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
            jdbc.update("INSERT INTO person_patron (person_id, patron_id) VALUES (?, ?)", personId, patronId);
        }
    }

    /**
     * Read back what is stored, in the same shape and with the same field names the fixtures use,
     * so earlier checkpoints' test data can be replayed and checked (AGENTS.md §4.3, §4.4). The
     * unnamed sheet's people are at the top level; each named sheet nests its own.
     */
    @GetMapping("/fixture")
    public Map<String, Object> fixture() {
        Map<String, Object> out = new LinkedHashMap<>(sheetContents(null));
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (String id : jdbc.queryForList("SELECT id FROM sheet ORDER BY id", String.class)) {
            Map<String, Object> sheet = new LinkedHashMap<>();
            sheet.put("id", id);
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
        out.put("people", jdbc.queryForList("SELECT id AS \"id\", name AS \"name\", alpha AS \"alpha\", "
                + "beta AS \"beta\", gamma AS \"gamma\" FROM person p WHERE " + where + " ORDER BY id", args));
        out.put("patrons", jdbc.queryForList("SELECT pp.person_id AS \"personId\", pp.patron_id AS \"patronId\" "
                + "FROM person_patron pp JOIN person p ON p.id = pp.person_id WHERE " + where
                + " ORDER BY pp.person_id, pp.patron_id", args));
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

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
