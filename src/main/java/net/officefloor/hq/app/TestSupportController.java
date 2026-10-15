package net.officefloor.hq.app;

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

    private static final Set<String> PERSON_FIELDS = Set.of("id", "name", "alpha", "beta");

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
        jdbc.execute("TRUNCATE TABLE person RESTART IDENTITY");
    }

    /**
     * Insert the fixture a spec needs; the payload shape evolves with the schema. Store every field
     * given, or refuse with HTTP 400 naming it — never ignore one silently (AGENTS.md §4.2).
     */
    @PostMapping("/seed")
    @Transactional
    public void seed(@RequestBody Map<String, Object> fixture) {
        for (String key : fixture.keySet()) {
            if (!key.equals("people")) {
                throw badRequest("unknown fixture key: " + key);
            }
        }
        for (Map<String, Object> row : rows(fixture.get("people"), "people")) {
            for (String field : row.keySet()) {
                if (!PERSON_FIELDS.contains(field)) {
                    throw badRequest("cannot store people." + field);
                }
            }
            if (!(row.get("name") instanceof String name)) {
                throw badRequest("people.name is required");
            }
            Object id = row.get("id");
            Integer alpha = integer(row.get("alpha"), "people.alpha");
            Integer beta = integer(row.get("beta"), "people.beta");
            if (id == null) {
                jdbc.update("INSERT INTO person (name, alpha, beta) VALUES (?, ?, ?)", name, alpha, beta);
            } else {
                jdbc.update("INSERT INTO person (id, name, alpha, beta) VALUES (?, ?, ?, ?)",
                        integer(id, "people.id"), name, alpha, beta);
            }
        }
        // Keep generated ids clear of the seeded ones.
        Long next = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM person", Long.class);
        jdbc.execute("ALTER TABLE person ALTER COLUMN id RESTART WITH " + next);
    }

    /**
     * Read back what is stored, in the same shape and with the same field names the fixtures use,
     * so earlier checkpoints' test data can be replayed and checked (AGENTS.md §4.3, §4.4).
     */
    @GetMapping("/fixture")
    public Map<String, Object> fixture() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("people", jdbc.queryForList("SELECT id AS \"id\", name AS \"name\", alpha AS \"alpha\", "
                + "beta AS \"beta\" "
                + "FROM person ORDER BY id"));
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
