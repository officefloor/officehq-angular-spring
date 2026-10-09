package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.invoice.InvoiceRequest;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import net.officefloor.hq.app.project.ProjectStatus;
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
    private final TestClock clock;

    public TestSupportController(Audit audit, JdbcTemplate jdbc, TestClock clock) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Truncate all domain tables and clear the audit file so each spec starts clean. */
    @PostMapping("/reset")
    @Transactional
    public void reset() {
        audit.clear();
        clock.reset();
        // Children first; H2 refuses to TRUNCATE a table referenced by a foreign key, so disable
        // referential checks for the duration of the truncates.
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbc.execute("TRUNCATE TABLE note RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE project_tag");
            jdbc.execute("TRUNCATE TABLE tag RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE task RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE contact RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE payment RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE invoice_line_item RESTART IDENTITY");
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
        // "asOf" pins the app's notion of today so date-based figures (e.g. overdue) are deterministic.
        if (fixture.get("asOf") != null) {
            clock.setToday(LocalDate.parse(fixture.get("asOf").toString()));
        }
        for (Map<String, Object> c : rows(fixture, "clients")) {
            jdbc.update("INSERT INTO client (id, name, email) VALUES (?, ?, ?)",
                    ((Number) c.get("id")).longValue(), c.get("name"), c.get("email"));
        }
        for (Map<String, Object> c : rows(fixture, "contacts")) {
            jdbc.update("INSERT INTO contact (id, client_id, name, email, role) VALUES (?, ?, ?, ?, ?)",
                    ((Number) c.get("id")).longValue(), ((Number) c.get("clientId")).longValue(),
                    c.get("name"), c.get("email"), c.get("role"));
            if (Boolean.TRUE.equals(c.get("primary"))) {
                jdbc.update("UPDATE client SET primary_contact_id = ? WHERE id = ?",
                        ((Number) c.get("id")).longValue(), ((Number) c.get("clientId")).longValue());
            }
        }
        for (Map<String, Object> p : rows(fixture, "projects")) {
            jdbc.update("INSERT INTO project (id, name, client_id, status, archived, budget) VALUES (?, ?, ?, ?, ?, ?)",
                    ((Number) p.get("id")).longValue(), p.get("name"),
                    ((Number) p.get("clientId")).longValue(),
                    p.get("status") == null ? ProjectStatus.ACTIVE.name() : p.get("status").toString(),
                    Boolean.TRUE.equals(p.get("archived")),
                    p.get("budget") == null ? null : new BigDecimal(p.get("budget").toString()));
        }
        for (Map<String, Object> t : rows(fixture, "tags")) {
            jdbc.update("INSERT INTO tag (id, name) VALUES (?, ?)",
                    ((Number) t.get("id")).longValue(), t.get("name"));
        }
        for (Map<String, Object> pt : rows(fixture, "projectTags")) {
            jdbc.update("INSERT INTO project_tag (project_id, tag_id) VALUES (?, ?)",
                    ((Number) pt.get("projectId")).longValue(), ((Number) pt.get("tagId")).longValue());
        }
        for (Map<String, Object> t : rows(fixture, "tasks")) {
            jdbc.update("INSERT INTO task (id, project_id, title, done) VALUES (?, ?, ?, ?)",
                    ((Number) t.get("id")).longValue(), ((Number) t.get("projectId")).longValue(),
                    t.get("title"), Boolean.TRUE.equals(t.get("done")));
        }
        for (Map<String, Object> n : rows(fixture, "notes")) {
            Instant at = n.get("at") == null ? clock.instant() : Instant.parse(n.get("at").toString());
            jdbc.update("INSERT INTO note (id, target_type, target_id, text, created_at) VALUES (?, ?, ?, ?, ?)",
                    ((Number) n.get("id")).longValue(), n.get("targetType"),
                    ((Number) n.get("targetId")).longValue(), n.get("text"), Timestamp.from(at));
        }
        for (Map<String, Object> i : rows(fixture, "invoices")) {
            // A missing date is filled in from the other one using the standard payment term; with
            // neither given the invoice is issued today.
            LocalDate issued = i.get("issuedDate") != null ? LocalDate.parse(i.get("issuedDate").toString())
                    : i.get("dueDate") != null
                            ? LocalDate.parse(i.get("dueDate").toString()).minusDays(InvoiceRequest.DEFAULT_TERM_DAYS)
                            : LocalDate.now(clock);
            LocalDate due = i.get("dueDate") == null ? issued.plusDays(InvoiceRequest.DEFAULT_TERM_DAYS)
                    : LocalDate.parse(i.get("dueDate").toString());
            long invoiceId = ((Number) i.get("id")).longValue();
            List<Map<String, Object>> lineItems = rows(i, "lineItems");
            // An invoice is the sum of its line items; a fixture giving only an amount describes an
            // invoice raised as one figure, which is carried as a single line.
            if (lineItems.isEmpty() && i.get("amount") != null) {
                lineItems = List.of(Map.of("description", InvoiceRequest.SINGLE_AMOUNT_DESCRIPTION,
                        "qty", 1, "unitPrice", i.get("amount")));
            }
            BigDecimal amount = BigDecimal.ZERO;
            for (Map<String, Object> l : lineItems) {
                amount = amount.add(new BigDecimal(l.get("qty").toString())
                        .multiply(new BigDecimal(l.get("unitPrice").toString())));
            }
            // A discount takes a percentage off the line items' subtotal; the amount is what is left.
            BigDecimal subtotal = amount.setScale(2, RoundingMode.HALF_UP);
            BigDecimal discountPct = i.get("discountPct") == null ? BigDecimal.ZERO
                    : new BigDecimal(i.get("discountPct").toString());
            BigDecimal discount = subtotal.multiply(discountPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            jdbc.update("INSERT INTO invoice (id, project_id, amount, discount_pct, status, issued_date, due_date)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    invoiceId, ((Number) i.get("projectId")).longValue(),
                    subtotal.subtract(discount), discountPct,
                    seedStatus(i.get("status")), issued, due);
            for (Map<String, Object> l : lineItems) {
                if (l.get("id") != null) {
                    jdbc.update("INSERT INTO invoice_line_item (id, invoice_id, description, qty, unit, unit_price)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                            ((Number) l.get("id")).longValue(), invoiceId, l.get("description"),
                            new BigDecimal(l.get("qty").toString()), l.get("unit"), new BigDecimal(l.get("unitPrice").toString()));
                } else {
                    jdbc.update("INSERT INTO invoice_line_item (invoice_id, description, qty, unit, unit_price)"
                            + " VALUES (?, ?, ?, ?, ?)",
                            invoiceId, l.get("description"),
                            new BigDecimal(l.get("qty").toString()), l.get("unit"), new BigDecimal(l.get("unitPrice").toString()));
                }
            }
        }
        for (Map<String, Object> p : rows(fixture, "payments")) {
            jdbc.update("INSERT INTO payment (id, invoice_id, amount, paid_date) VALUES (?, ?, ?, ?)",
                    ((Number) p.get("id")).longValue(), ((Number) p.get("invoiceId")).longValue(),
                    new BigDecimal(p.get("amount").toString()), LocalDate.parse(p.get("date").toString()));
        }
        // Continue generated ids after the explicitly seeded ones.
        restartIdentity("client");
        restartIdentity("contact");
        restartIdentity("project");
        restartIdentity("invoice");
        restartIdentity("invoice_line_item");
        restartIdentity("task");
        restartIdentity("tag");
        restartIdentity("note");
        restartIdentity("payment");
    }

    /**
     * Invoices without a status are seeded as new drafts. Older fixtures may describe an
     * issued-but-unpaid invoice as "UNPAID"; this domain models that state as SENT.
     */
    private static String seedStatus(Object status) {
        if (status == null) {
            return InvoiceStatus.DRAFT.name();
        }
        if ("UNPAID".equals(status)) {
            return InvoiceStatus.SENT.name();
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
