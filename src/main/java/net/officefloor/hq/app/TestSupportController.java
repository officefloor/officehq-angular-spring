package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
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

    private static final Set<String> FIXTURE_KEYS = Set.of("asOf", "settings", "clients", "invoices", "payments");
    private static final Set<String> SETTINGS_FIELDS = Set.of("taxRate");
    private static final Set<String> CLIENT_FIELDS = Set.of("id", "name", "email");
    private static final Set<String> INVOICE_FIELDS =
            Set.of("id", "clientId", "status", "issueDate", "dueDate", "discountPct", "lineItems");
    private static final Set<String> LINE_FIELDS = Set.of("id", "description", "qty", "unitPrice", "taxExempt", "discountPct");
    private static final Set<String> PAYMENT_FIELDS = Set.of("id", "clientId", "amount", "date", "reference", "allocations");
    private static final Set<String> ALLOCATION_FIELDS = Set.of("invoiceId", "amount");
    private static final Set<String> STATUSES = Set.of("DRAFT", "SENT", "VOID");

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
        jdbc.execute("DELETE FROM app_setting");
        jdbc.execute("DELETE FROM payment_allocation");
        for (String table : List.of("payment", "invoice_line", "invoice", "client")) {
            jdbc.execute("DELETE FROM " + table);
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH 1");
        }
    }

    /**
     * Insert the fixture a spec needs; the payload shape evolves with the schema. Store every field
     * given, or refuse with HTTP 400 naming it — never ignore one silently (AGENTS.md §4.2).
     */
    @PostMapping("/seed")
    @Transactional
    public void seed(@RequestBody Map<String, Object> fixture) {
        for (String key : fixture.keySet()) {
            if (!FIXTURE_KEYS.contains(key)) {
                throw refuse("unsupported fixture key: " + key);
            }
        }
        if (fixture.get("asOf") != null) {
            LocalDate asOf = date(fixture.get("asOf"), "asOf");
            jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)", Today.AS_OF, asOf.toString());
        }
        if (fixture.get("settings") != null) {
            if (!(fixture.get("settings") instanceof Map<?, ?> settings)) {
                throw refuse("settings must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) settings;
            only(values, SETTINGS_FIELDS, "settings");
            if (values.get("taxRate") != null) {
                BigDecimal rate = percent(values.get("taxRate"), "settings.taxRate");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)", TaxRate.TAX_RATE,
                        rate.toPlainString());
            }
        }
        for (Map<String, Object> row : rows(fixture.get("clients"), "clients")) {
            only(row, CLIENT_FIELDS, "clients[]");
            Object id = row.get("id");
            if (id == null) {
                jdbc.update("INSERT INTO client (name, email) VALUES (?, ?)",
                        text(row, "name"), text(row, "email"));
            } else {
                jdbc.update("INSERT INTO client (id, name, email) VALUES (?, ?, ?)",
                        id(id), text(row, "name"), text(row, "email"));
            }
        }
        for (Map<String, Object> row : rows(fixture.get("invoices"), "invoices")) {
            only(row, INVOICE_FIELDS, "invoices[]");
            String status = row.get("status") == null ? "DRAFT" : row.get("status").toString();
            if (!STATUSES.contains(status)) {
                throw refuse("unsupported value of invoices[].status: " + status);
            }
            if (row.get("clientId") == null) {
                throw refuse("invoices[].clientId is required");
            }
            Long invoiceId = insert("invoice", row.get("id"),
                    new String[] {"client_id", "status", "issue_date", "due_date", "discount_pct"},
                    id(row.get("clientId")), status,
                    row.get("issueDate") == null ? null : date(row.get("issueDate"), "invoices[].issueDate"),
                    row.get("dueDate") == null ? null : date(row.get("dueDate"), "invoices[].dueDate"),
                    percent(row.get("discountPct"), "invoices[].discountPct"));
            for (Map<String, Object> line : rows(row.get("lineItems"), "invoices[].lineItems")) {
                only(line, LINE_FIELDS, "invoices[].lineItems[]");
                insert("invoice_line", line.get("id"),
                        new String[] {"invoice_id", "description", "qty", "unit_price", "tax_exempt", "discount_pct"},
                        invoiceId, text(line, "description"),
                        cents(line.get("qty"), "invoices[].lineItems[].qty"),
                        cents(line.get("unitPrice"), "invoices[].lineItems[].unitPrice"),
                        flag(line.get("taxExempt"), "invoices[].lineItems[].taxExempt"),
                        percent(line.get("discountPct"), "invoices[].lineItems[].discountPct"));
            }
        }
        for (Map<String, Object> row : rows(fixture.get("payments"), "payments")) {
            only(row, PAYMENT_FIELDS, "payments[]");
            if (row.get("clientId") == null) {
                throw refuse("payments[].clientId is required");
            }
            if (row.get("date") == null) {
                throw refuse("payments[].date is required");
            }
            Long paymentId = insert("payment", row.get("id"), new String[] {"client_id", "amount", "payment_date", "reference"},
                    id(row.get("clientId")), cents(row.get("amount"), "payments[].amount"),
                    date(row.get("date"), "payments[].date"),
                    row.get("reference") == null ? null : row.get("reference").toString());
            for (Map<String, Object> allocation : rows(row.get("allocations"), "payments[].allocations")) {
                only(allocation, ALLOCATION_FIELDS, "payments[].allocations[]");
                if (allocation.get("invoiceId") == null) {
                    throw refuse("payments[].allocations[].invoiceId is required");
                }
                jdbc.update("INSERT INTO payment_allocation (payment_id, invoice_id, amount) VALUES (?, ?, ?)",
                        paymentId, id(allocation.get("invoiceId")),
                        cents(allocation.get("amount"), "payments[].allocations[].amount"));
            }
        }
        // Keep generated ids clear of the seeded ones.
        for (String table : List.of("client", "invoice", "invoice_line", "payment")) {
            Long next = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM " + table, Long.class);
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + next);
        }
    }

    /**
     * Read back what is stored, in the same shape and with the same field names the fixtures use,
     * so earlier checkpoints' test data can be replayed and checked (AGENTS.md §4.3, §4.4).
     */
    @GetMapping("/fixture")
    public Map<String, Object> fixture() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> asOf = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?", String.class,
                Today.AS_OF);
        if (!asOf.isEmpty()) {
            out.put("asOf", asOf.get(0));
        }
        List<String> taxRate = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, TaxRate.TAX_RATE);
        if (!taxRate.isEmpty()) {
            out.put("settings", Map.of("taxRate", new BigDecimal(taxRate.get(0))));
        }
        out.put("clients", jdbc.queryForList("SELECT id, name, email FROM client ORDER BY id").stream()
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.get("ID"));
                    row.put("name", r.get("NAME"));
                    row.put("email", r.get("EMAIL"));
                    return row;
                }).toList());
        out.put("invoices", jdbc.queryForList(
                "SELECT id, client_id, status, issue_date, due_date, discount_pct FROM invoice ORDER BY id").stream()
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.get("ID"));
                    row.put("clientId", r.get("CLIENT_ID"));
                    row.put("status", r.get("STATUS"));
                    row.put("issueDate", r.get("ISSUE_DATE") == null ? null : r.get("ISSUE_DATE").toString());
                    row.put("dueDate", r.get("DUE_DATE") == null ? null : r.get("DUE_DATE").toString());
                    row.put("discountPct", r.get("DISCOUNT_PCT"));
                    row.put("lineItems", jdbc.queryForList(
                            "SELECT id, description, qty, unit_price, tax_exempt, discount_pct FROM invoice_line WHERE invoice_id = ? ORDER BY id",
                            r.get("ID")).stream().map(l -> {
                                Map<String, Object> line = new LinkedHashMap<>();
                                line.put("id", l.get("ID"));
                                line.put("description", l.get("DESCRIPTION"));
                                line.put("qty", l.get("QTY"));
                                line.put("unitPrice", l.get("UNIT_PRICE"));
                                line.put("taxExempt", l.get("TAX_EXEMPT"));
                                line.put("discountPct", l.get("DISCOUNT_PCT"));
                                return line;
                            }).toList());
                    return row;
                }).toList());
        out.put("payments", jdbc.queryForList("SELECT id, client_id, amount, payment_date, reference FROM payment ORDER BY id")
                .stream().map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.get("ID"));
                    row.put("clientId", r.get("CLIENT_ID"));
                    row.put("amount", r.get("AMOUNT"));
                    row.put("date", r.get("PAYMENT_DATE").toString());
                    row.put("reference", r.get("REFERENCE"));
                    row.put("allocations", jdbc.queryForList(
                            "SELECT invoice_id, amount FROM payment_allocation WHERE payment_id = ? ORDER BY invoice_id",
                            r.get("ID")).stream().map(a -> {
                                Map<String, Object> allocation = new LinkedHashMap<>();
                                allocation.put("invoiceId", a.get("INVOICE_ID"));
                                allocation.put("amount", a.get("AMOUNT"));
                                return allocation;
                            }).toList());
                    return row;
                }).toList());
        return out;
    }

    /** Insert a row with the given id, or a generated one when none is given; returns the id. */
    private Long insert(String table, Object id, String[] columns, Object... values) {
        String cols = String.join(", ", columns);
        String marks = String.join(", ", Collections.nCopies(columns.length, "?"));
        if (id != null) {
            Object[] args = new Object[values.length + 1];
            args[0] = id(id);
            System.arraycopy(values, 0, args, 1, values.length);
            jdbc.update("INSERT INTO " + table + " (id, " + cols + ") VALUES (?, " + marks + ")", args);
            return id(id);
        }
        jdbc.update("INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")", values);
        return jdbc.queryForObject("SELECT MAX(id) FROM " + table, Long.class);
    }

    /** A true/false flag; false when left out. */
    private static boolean flag(Object value, String where) {
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean b)) {
            throw refuse(where + " must be true or false");
        }
        return b;
    }

    private static void only(Map<String, Object> row, Set<String> fields, String where) {
        for (String field : row.keySet()) {
            if (!fields.contains(field)) {
                throw refuse("unsupported field: " + where + "." + field);
            }
        }
    }

    private static Long id(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw refuse("not an id: " + value);
        }
    }

    private static LocalDate date(Object value, String field) {
        try {
            return LocalDate.parse(value.toString());
        } catch (DateTimeParseException e) {
            throw refuse(field + " must be a yyyy-mm-dd date, not " + value);
        }
    }

    /** A number stored to the cent; refuses a value it could only store by rounding. */
    private static BigDecimal cents(Object value, String field) {
        if (value == null) {
            throw refuse(field + " is required");
        }
        BigDecimal number;
        try {
            number = new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            throw refuse(field + " must be a number, not " + value);
        }
        if (number.stripTrailingZeros().scale() > 2) {
            throw refuse(field + " can have at most two decimals, not " + value);
        }
        return number;
    }

    /** A percentage from 0 to 100 with at most two decimals; none given is zero. */
    private static BigDecimal percent(Object value, String field) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal number = cents(value, field);
        if (number.signum() < 0 || number.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw refuse(field + " must be from 0 to 100, not " + value);
        }
        return number;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value, String key) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw refuse(key + " must be a list of rows");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw refuse(key + " must be a list of rows");
            }
            out.add((Map<String, Object>) m);
        }
        return out;
    }

    private static String text(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : v.toString();
    }

    private static ResponseStatusException refuse(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
