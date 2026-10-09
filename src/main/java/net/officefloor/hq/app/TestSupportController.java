package net.officefloor.hq.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.currency.Currency;
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
            jdbc.execute("TRUNCATE TABLE credit_note RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE payment RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE client_payment RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE refund RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE deposit_application RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE deposit RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE invoice_line_item RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE invoice_discount RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE invoice RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE project RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE client RESTART IDENTITY");
            jdbc.execute("TRUNCATE TABLE fx_rate RESTART IDENTITY");
            // Back to the standard currencies, each rounding to the cent and shown with two decimals.
            jdbc.execute("DELETE FROM currency");
            jdbc.execute("INSERT INTO currency (code, symbol, rounding_step) VALUES ('USD', '$', 0.01), ('EUR', '€', 0.01),"
                    + " ('GBP', '£', 0.01), ('CAD', 'CA$', 0.01), ('AUD', 'A$', 0.01)");
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        jdbc.update("UPDATE app_settings SET default_tax_pct = 0, home_currency = 'USD' WHERE id = 1");
    }

    /** Insert the fixture a spec needs; the payload shape evolves with the schema. */
    @PostMapping("/seed")
    @Transactional
    public void seed(@RequestBody Map<String, Object> fixture) {
        // "asOf" pins the app's notion of today so date-based figures (e.g. overdue) are deterministic.
        if (fixture.get("asOf") != null) {
            clock.setToday(LocalDate.parse(fixture.get("asOf").toString()));
        }
        // A fixture currency is added, or replaces the standard one with the same code; its rounding step
        // defaults to the cent, its decimals to two and its symbol to its code.
        for (Map<String, Object> c : rows(fixture, "currencies")) {
            jdbc.update("MERGE INTO currency (code, symbol, rounding_step, decimals) KEY (code) VALUES (?, ?, ?, ?)",
                    c.get("code"), c.get("symbol") == null ? c.get("code") + " " : c.get("symbol"),
                    c.get("roundingStep") == null ? new BigDecimal("0.01") : decimal(c.get("roundingStep")),
                    c.get("decimals") == null ? 2 : ((Number) c.get("decimals")).intValue());
        }
        if (fixture.get("homeCurrency") != null) {
            jdbc.update("UPDATE app_settings SET home_currency = ? WHERE id = 1", fixture.get("homeCurrency").toString());
        }
        // An exchange rate: from its date, one unit of the currency is worth "rate" units of the home currency.
        for (Map<String, Object> r : rows(fixture, "fxRates")) {
            jdbc.update("INSERT INTO fx_rate (currency, rate_date, rate) VALUES (?, ?, ?)",
                    r.get("currency"), LocalDate.parse(r.get("date").toString()), decimal(r.get("rate")));
        }
        for (Map<String, Object> c : rows(fixture, "clients")) {
            jdbc.update("INSERT INTO client (id, name, email, phone, tax_number, billing_address, language, tax_inclusive, tax_exempt, key_account, archived, currency, default_discount_pct)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ((Number) c.get("id")).longValue(), c.get("name"), c.get("email"), c.get("phone"), c.get("taxNumber"), c.get("billingAddress"), c.get("language"),
                    Boolean.TRUE.equals(c.get("taxInclusive")), Boolean.TRUE.equals(c.get("taxExempt")),
                    Boolean.TRUE.equals(c.get("keyAccount")),
                    Boolean.TRUE.equals(c.get("archived")),
                    c.get("currency") == null ? Currency.DEFAULT : c.get("currency").toString(),
                    decimal(c.get("defaultDiscountPct")));
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
            jdbc.update("INSERT INTO project (id, name, code, description, client_id, status, archived, budget, billable, closed)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ((Number) p.get("id")).longValue(), p.get("name"), p.get("code"), p.get("description"),
                    ((Number) p.get("clientId")).longValue(),
                    p.get("status") == null ? ProjectStatus.ACTIVE.name() : p.get("status").toString(),
                    Boolean.TRUE.equals(p.get("archived")),
                    p.get("budget") == null ? null : new BigDecimal(p.get("budget").toString()),
                    !Boolean.FALSE.equals(p.get("billable")), Boolean.TRUE.equals(p.get("closed")));
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
            jdbc.update("INSERT INTO task (id, project_id, title, done, due_date, assignee) VALUES (?, ?, ?, ?, ?, ?)",
                    ((Number) t.get("id")).longValue(), ((Number) t.get("projectId")).longValue(),
                    t.get("title"), Boolean.TRUE.equals(t.get("done")),
                    t.get("dueDate") == null ? null : LocalDate.parse(t.get("dueDate").toString()),
                    t.get("assignee"));
        }
        for (Map<String, Object> n : rows(fixture, "notes")) {
            Instant at = n.get("at") == null ? clock.instant() : seedInstant(n.get("at").toString());
            jdbc.update("INSERT INTO note (id, target_type, target_id, text, created_at) VALUES (?, ?, ?, ?, ?)",
                    ((Number) n.get("id")).longValue(), n.get("targetType"),
                    ((Number) n.get("targetId")).longValue(), n.get("text"), Timestamp.from(at));
        }
        for (Map<String, Object> i : rows(fixture, "invoices")) {
            // A missing date is filled in from the other one using the standard payment term; with
            // neither given the invoice is issued today.
            // The issue date may be given as "issuedDate" or "issueDate".
            Object issuedValue = i.get("issuedDate") != null ? i.get("issuedDate") : i.get("issueDate");
            LocalDate issued = issuedValue != null ? LocalDate.parse(issuedValue.toString())
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
            BigDecimal taxPct = i.get("taxPct") == null ? BigDecimal.ZERO
                    : new BigDecimal(i.get("taxPct").toString());
            BigDecimal levyPct = i.get("levyPct") == null ? BigDecimal.ZERO
                    : new BigDecimal(i.get("levyPct").toString());
            // Each line is rounded to the cent first and the rounded lines are added up. A discount takes a
            // percentage off the subtotal; sales tax and a levy each add their percentage of every taxable
            // line after its discount, rounded per line and then added up. The amount is the discounted
            // subtotal plus the tax plus the levy.
            // An invoice for a tax-exempt client carries no tax or levy on any line.
            long projectId = ((Number) i.get("projectId")).longValue();
            Map<String, Object> client = jdbc.queryForMap(
                    "SELECT c.tax_inclusive, c.tax_exempt, c.default_discount_pct FROM project p JOIN client c ON c.id = p.client_id WHERE p.id = ?",
                    projectId);
            boolean taxInclusive = Boolean.TRUE.equals(client.get("TAX_INCLUSIVE"));
            boolean taxExempt = Boolean.TRUE.equals(client.get("TAX_EXEMPT"));
            // An invoice may carry several discounts, each a percentage ("pct") or a flat amount ("amount");
            // an older fixture gives at most one as discountPct / discountAmount. A percentage may be capped
            // at the most it takes off ("cap", or discountCap alongside discountPct).
            List<Map<String, Object>> discounts = new ArrayList<>(rows(i, "discounts"));
            if (i.get("discountPct") != null && new BigDecimal(i.get("discountPct").toString()).signum() > 0) {
                Map<String, Object> pctDiscount = new HashMap<>(Map.of("pct", i.get("discountPct")));
                if (i.get("discountCap") != null) {
                    pctDiscount.put("cap", i.get("discountCap"));
                }
                discounts.add(pctDiscount);
            }
            if (i.get("discountAmount") != null && new BigDecimal(i.get("discountAmount").toString()).signum() > 0) {
                discounts.add(Map.of("amount", i.get("discountAmount")));
            }
            // An invoice whose fixture says nothing about discounts starts with the client's standard one, as a
            // newly raised invoice does.
            BigDecimal clientDefaultPct = decimal(client.get("DEFAULT_DISCOUNT_PCT"));
            if (i.get("discounts") == null && i.get("discountPct") == null && i.get("discountAmount") == null
                    && clientDefaultPct.signum() > 0) {
                discounts.add(Map.of("pct", clientDefaultPct));
            }
            // The percentages each take their share of the subtotal (never more than is left); the flat
            // amounts are then taken off what is left and shared across the lines in proportion to what
            // each charges.
            // Each line is first taken down by its own discount, if it has one.
            List<BigDecimal> lines = lineItems.stream().map(l -> {
                BigDecimal gross = new BigDecimal(l.get("qty").toString())
                        .multiply(new BigDecimal(l.get("unitPrice").toString())).setScale(2, RoundingMode.HALF_UP);
                return gross.subtract(gross.multiply(lineDiscountPct(l)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            }).toList();
            BigDecimal subtotal = lines.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            // A percentage whose cap limits it is shared across the lines like a flat amount; the others are
            // taken off each line as a percentage.
            BigDecimal discountPct = BigDecimal.ZERO;
            BigDecimal cappedDiscount = BigDecimal.ZERO.setScale(2);
            BigDecimal left = subtotal;
            for (Map<String, Object> d : discounts) {
                if (decimal(d.get("pct")).signum() > 0) {
                    BigDecimal share = subtotal.multiply(decimal(d.get("pct"))).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    boolean capped = d.get("cap") != null && share.compareTo(decimal(d.get("cap"))) > 0;
                    BigDecimal taken = (capped ? decimal(d.get("cap")).setScale(2, RoundingMode.HALF_UP) : share)
                            .min(left.max(BigDecimal.ZERO.setScale(2)));
                    if (capped) {
                        cappedDiscount = cappedDiscount.add(taken);
                    } else {
                        discountPct = discountPct.add(decimal(d.get("pct")));
                    }
                    left = left.subtract(taken);
                }
            }
            discountPct = discountPct.min(BigDecimal.valueOf(100));
            BigDecimal pctDiscount = subtotal.subtract(left);
            for (Map<String, Object> d : discounts) {
                if (decimal(d.get("pct")).signum() == 0) {
                    left = left.subtract(decimal(d.get("amount")).setScale(2, RoundingMode.HALF_UP)
                            .min(left.max(BigDecimal.ZERO.setScale(2))));
                }
            }
            BigDecimal flatDiscount = subtotal.subtract(left).subtract(pctDiscount);
            BigDecimal sharedDiscount = flatDiscount.add(cappedDiscount);
            BigDecimal tax = BigDecimal.ZERO.setScale(2);
            BigDecimal levy = BigDecimal.ZERO.setScale(2);
            for (int n = 0; n < lineItems.size(); n++) {
                BigDecimal line = lines.get(n);
                if (!taxExempt && !Boolean.TRUE.equals(lineItems.get(n).get("taxExempt"))) {
                    BigDecimal flatShare = subtotal.signum() == 0 ? BigDecimal.ZERO
                            : sharedDiscount.multiply(line).divide(subtotal, 2, RoundingMode.HALF_UP);
                    BigDecimal base = line.subtract(
                            line.multiply(discountPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                            .subtract(flatShare);
                    tax = tax.add(base.multiply(taxPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
                    levy = levy.add(base.multiply(levyPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
                }
            }
            BigDecimal discount = pctDiscount.add(flatDiscount);
            BigDecimal discounted = subtotal.subtract(discount);
            // An invoice for a tax-inclusive client already has the taxes inside its prices, so its amount is
            // just the discounted subtotal.
            // An early-payment discount offered is just recorded; it does not change the amount owed.
            BigDecimal earlyPaymentPct = i.get("earlyPaymentPct") == null ? BigDecimal.ZERO
                    : new BigDecimal(i.get("earlyPaymentPct").toString());
            // A flat surcharge (such as a handling fee) is added on last, after any tax.
            BigDecimal surcharge = i.get("surcharge") == null ? BigDecimal.ZERO
                    : new BigDecimal(i.get("surcharge").toString());
            // A minimum charge is billed instead when the net total comes out under it.
            BigDecimal minimumCharge = decimal(i.get("minimumCharge"));
            int earlyPaymentDays = i.get("earlyPaymentDays") == null ? 0 : ((Number) i.get("earlyPaymentDays")).intValue();
            // A late fee per day accrues once the invoice is overdue; it does not change the amount invoiced.
            BigDecimal lateFeePerDay = decimal(i.get("lateFeePerDay"));
            jdbc.update("INSERT INTO invoice (id, project_id, amount, tax_pct, levy_pct, surcharge, tax_inclusive, tax_exempt,"
                    + " early_payment_pct, early_payment_days, status, issued_date, due_date, minimum_charge, late_fee_per_day)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    invoiceId, projectId,
                    (taxInclusive ? discounted : discounted.add(tax).add(levy)).add(surcharge).max(minimumCharge), taxPct, levyPct,
                    surcharge, taxInclusive, taxExempt,
                    earlyPaymentPct, earlyPaymentDays, seedStatus(i.get("status")), issued, due, minimumCharge, lateFeePerDay);
            for (Map<String, Object> d : discounts) {
                if (d.get("id") != null) {
                    jdbc.update("INSERT INTO invoice_discount (id, invoice_id, discount_pct, discount_amount, discount_cap) VALUES (?, ?, ?, ?, ?)",
                            ((Number) d.get("id")).longValue(), invoiceId, decimal(d.get("pct")), decimal(d.get("amount")),
                            d.get("cap") == null ? null : decimal(d.get("cap")));
                } else {
                    jdbc.update("INSERT INTO invoice_discount (invoice_id, discount_pct, discount_amount, discount_cap) VALUES (?, ?, ?, ?)",
                            invoiceId, decimal(d.get("pct")), decimal(d.get("amount")), d.get("cap") == null ? null : decimal(d.get("cap")));
                }
            }
            for (Map<String, Object> l : lineItems) {
                if (l.get("id") != null) {
                    jdbc.update("INSERT INTO invoice_line_item (id, invoice_id, description, qty, unit, unit_price, tax_exempt, discount_pct)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            ((Number) l.get("id")).longValue(), invoiceId, l.get("description"),
                            new BigDecimal(l.get("qty").toString()), l.get("unit"), new BigDecimal(l.get("unitPrice").toString()),
                            Boolean.TRUE.equals(l.get("taxExempt")), lineDiscountPct(l));
                } else {
                    jdbc.update("INSERT INTO invoice_line_item (invoice_id, description, qty, unit, unit_price, tax_exempt, discount_pct)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                            invoiceId, l.get("description"),
                            new BigDecimal(l.get("qty").toString()), l.get("unit"), new BigDecimal(l.get("unitPrice").toString()),
                            Boolean.TRUE.equals(l.get("taxExempt")), lineDiscountPct(l));
                }
            }
        }
        for (Map<String, Object> p : rows(fixture, "payments")) {
            jdbc.update("INSERT INTO payment (id, invoice_id, amount, paid_date) VALUES (?, ?, ?, ?)",
                    ((Number) p.get("id")).longValue(), ((Number) p.get("invoiceId")).longValue(),
                    new BigDecimal(p.get("amount").toString()), LocalDate.parse(p.get("date").toString()));
        }
        for (Map<String, Object> c : rows(fixture, "creditNotes")) {
            jdbc.update("INSERT INTO credit_note (id, invoice_id, amount, issued_at) VALUES (?, ?, ?, ?)",
                    ((Number) c.get("id")).longValue(), ((Number) c.get("invoiceId")).longValue(),
                    new BigDecimal(c.get("amount").toString()),
                    Timestamp.from(seedInstant(c.get("date").toString())));
        }
        for (Map<String, Object> d : rows(fixture, "deposits")) {
            jdbc.update("INSERT INTO deposit (id, client_id, amount, paid_date) VALUES (?, ?, ?, ?)",
                    ((Number) d.get("id")).longValue(), ((Number) d.get("clientId")).longValue(),
                    new BigDecimal(d.get("amount").toString()), LocalDate.parse(d.get("date").toString()));
        }
        // Continue generated ids after the explicitly seeded ones.
        restartIdentity("client");
        restartIdentity("contact");
        restartIdentity("project");
        restartIdentity("invoice");
        restartIdentity("invoice_line_item");
        restartIdentity("invoice_discount");
        restartIdentity("task");
        restartIdentity("tag");
        restartIdentity("note");
        restartIdentity("payment");
        restartIdentity("credit_note");
        restartIdentity("deposit");
    }

    /** A fixture timestamp, either a full instant or a bare date taken as the start of that day (UTC). */
    private static Instant seedInstant(String value) {
        return value.contains("T") ? Instant.parse(value) : LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
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

    /** A fixture number, zero when it is not given. */
    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    @SuppressWarnings("unchecked")
    /** The percentage a fixture line item takes off itself; none when it does not say. */
    private static BigDecimal lineDiscountPct(Map<String, Object> lineItem) {
        return lineItem.get("discountPct") == null ? BigDecimal.ZERO
                : new BigDecimal(lineItem.get("discountPct").toString());
    }

    private static List<Map<String, Object>> rows(Map<String, Object> fixture, String key) {
        Object value = fixture.get(key);
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }
}
