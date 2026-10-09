package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.invoice.InvoiceRequest;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-spec data setup for the end-to-end tests. Profile-guarded so it exists ONLY under the test
 * launch (bin/start sets spring.profiles.active=e2e) — never in a real deploy. Tests call these to
 * ARRANGE data; they ASSERT only through the UI.
 */
@Profile("e2e")
@RestController
@RequestMapping("/__test__")
public class TestSupportController {

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
        // Children first; H2 refuses to TRUNCATE a table referenced by a foreign key, so disable
        // referential checks for the duration of the truncates.
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbc.execute("TRUNCATE TABLE invoice RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE project RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE client RESTART IDENTITY");
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    /** Insert the fixture a spec needs; the payload shape evolves with the schema. */
    @PostMapping("/seed")
    @Transactional
    public void seed(@RequestBody Map<String, Object> fixture) {
        for (Map<String, Object> c : rows(fixture, "clients")) {
            jdbc.update("INSERT INTO client (id, name, email) VALUES (?, ?, ?)",
                    ((Number) c.get("id")).longValue(), c.get("name"), c.get("email"));
        }
        for (Map<String, Object> p : rows(fixture, "projects")) {
            jdbc.update("INSERT INTO project (id, name, client_id) VALUES (?, ?, ?)",
                    ((Number) p.get("id")).longValue(), p.get("name"),
                    ((Number) p.get("clientId")).longValue());
        }
        for (Map<String, Object> i : rows(fixture, "invoices")) {
            LocalDate issued = i.get("issuedDate") == null ? LocalDate.now()
                    : LocalDate.parse(i.get("issuedDate").toString());
            LocalDate due = i.get("dueDate") == null ? issued.plusDays(InvoiceRequest.DEFAULT_TERM_DAYS)
                    : LocalDate.parse(i.get("dueDate").toString());
            jdbc.update("INSERT INTO invoice (id, project_id, amount, status, issued_date, due_date)"
                    + " VALUES (?, ?, ?, ?, ?, ?)",
                    ((Number) i.get("id")).longValue(), ((Number) i.get("projectId")).longValue(),
                    new BigDecimal(i.get("amount").toString()),
                    seedStatus(i.get("status")), issued, due);
        }
        // Continue generated ids after the explicitly seeded ones.
        restartIdentity("client");
        restartIdentity("project");
        restartIdentity("invoice");
    }

    /**
     * Fixtures may describe an issued-but-unpaid invoice as "SENT"; this domain models that state
     * as UNPAID.
     */
    private static String seedStatus(Object status) {
        if (status == null || "SENT".equals(status)) {
            return InvoiceStatus.UNPAID.name();
        }
        return status.toString();
    }

    private void restartIdentity(String table) {
        Long next = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM " + table, Long.class);
        jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + next);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> fixture, String key) {
        Object value = fixture.get(key);
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }
}
