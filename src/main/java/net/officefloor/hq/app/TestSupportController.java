package net.officefloor.hq.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    private static final Set<String> PERSON_FIELDS = Set.of("id", "name", "alpha", "beta", "gamma", "hold");
    private static final Set<String> PATRON_FIELDS = Set.of("personId", "patronId");

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
        jdbc.update("DELETE FROM person_patron");
        jdbc.update("DELETE FROM person");
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
            if (!key.equals("people") && !key.equals("patrons")) {
                throw badRequest("unsupported fixture field: " + key);
            }
        }
        for (Map<String, Object> row : rows(fixture, "people")) {
            checkFields(row, PERSON_FIELDS, "people");
            Object name = row.getOrDefault("name", "");
            jdbc.update("INSERT INTO person (id, name, alpha, beta, gamma, hold) VALUES (?, ?, ?, ?, ?, ?)",
                    id(row, "id", "people"), name == null ? "" : name.toString(),
                    integer(row, "alpha"), integer(row, "beta"), integer(row, "gamma"),
                    Boolean.TRUE.equals(row.get("hold")));
        }
        for (Map<String, Object> row : rows(fixture, "patrons")) {
            checkFields(row, PATRON_FIELDS, "patrons");
            jdbc.update("INSERT INTO person_patron (person_id, patron_id) VALUES (?, ?)",
                    id(row, "personId", "patrons"), id(row, "patronId", "patrons"));
        }
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM person", Long.class);
        jdbc.execute("ALTER TABLE person ALTER COLUMN id RESTART WITH " + (max + 1));
    }

    /**
     * Read back what is stored, in the same shape and with the same field names the fixtures use,
     * so the app's test data sets can be checked (AGENTS.md §4.3, §4.4).
     */
    @GetMapping("/fixture")
    @Transactional(readOnly = true)
    public Map<String, Object> fixture() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("people", jdbc.queryForList(
                "SELECT id AS \"id\", name AS \"name\", alpha AS \"alpha\", beta AS \"beta\", gamma AS \"gamma\","
                        + " hold AS \"hold\" FROM person ORDER BY id"));
        out.put("patrons", jdbc.queryForList(
                "SELECT person_id AS \"personId\", patron_id AS \"patronId\" FROM person_patron"
                        + " ORDER BY person_id, patron_id"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> fixture, String key) {
        Object value = fixture.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw badRequest(key + " must be a list of rows");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : list) {
            if (!(row instanceof Map<?, ?>)) {
                throw badRequest(key + " must be a list of rows");
            }
            rows.add((Map<String, Object>) row);
        }
        return rows;
    }

    private static void checkFields(Map<String, Object> row, Set<String> allowed, String collection) {
        for (String field : row.keySet()) {
            if (!allowed.contains(field)) {
                throw badRequest("unsupported field: " + collection + "." + field);
            }
        }
    }

    private static long id(Map<String, Object> row, String field, String collection) {
        Integer value = integer(row, field);
        if (value == null) {
            throw badRequest(collection + "." + field + " is required");
        }
        return value;
    }

    private static Integer integer(Map<String, Object> row, String field) {
        Object value = row.get(field);
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

    /** Answer a refused fixture with its reason, so the caller sees which field was refused. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> refused(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("status", e.getStatusCode().value(), "message", String.valueOf(e.getReason())));
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
