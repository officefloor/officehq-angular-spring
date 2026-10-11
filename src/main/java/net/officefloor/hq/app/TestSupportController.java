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
import jakarta.persistence.EntityManager;
import net.officefloor.hq.app.clients.Client;
import net.officefloor.hq.app.clients.Currency;
import net.officefloor.hq.app.clients.RateTiers;
import net.officefloor.hq.app.invoices.Invoice;
import net.officefloor.hq.app.invoices.InvoiceRepository;
import net.officefloor.hq.app.invoices.PaymentMethod;
import net.officefloor.hq.app.invoices.PaymentRepository;
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
    private static final Set<String> SETTINGS_FIELDS = Set.of("taxRate", "usdPer", "fxRates", "lateInterestRate", "lateFee", "regionTaxRates", "homeRegion", "minimumCharge");
    /** The setting holding the workspace's minimum charge in US dollars (see Invoice#minimumCharge). */
    private static final String MINIMUM_CHARGE = "minimum_charge";
    private static final Set<String> FX_RATE_FIELDS = Set.of("from", "currency", "usdPer");
    private static final Set<String> CLIENT_FIELDS = Set.of("id", "name", "email", "termsDays", "discountPct", "taxExempt", "pricesIncludeTax", "currency", "earlyPayPct", "earlyPayDays", "retentionPct", "graceDays", "interestRate", "region", "taxNumber", "rates");
    private static final Set<String> INVOICE_FIELDS =
            Set.of("id", "clientId", "status", "issueDate", "dueDate", "discountPct", "discountAmount", "discountCap", "discountReason", "manualDueDate", "creditOf", "retentionPct", "retentionCap", "retentionReleasedOn", "retentionReleasedAmount", "instalments", "instalmentRate", "planDownPct", "interestWaivedOn", "writtenOffOn", "skippedInstalment", "lineItems");
    private static final Set<String> LINE_FIELDS = Set.of("id", "description", "qty", "unitPrice", "taxExempt", "discountPct", "taxRate", "creditOfLine", "unitType");
    private static final Set<String> PAYMENT_FIELDS = Set.of("id", "clientId", "amount", "date", "reference", "reversed", "refunded", "creditNote", "allocations", "currency", "method");
    private static final Set<String> ALLOCATION_FIELDS = Set.of("invoiceId", "amount", "date");
    private static final Set<String> STATUSES = Set.of("DRAFT", "SENT", "VOID", "CREDIT_NOTE");

    private final Audit audit;
    private final JdbcTemplate jdbc;
    private final InvoiceRepository invoices;
    private final TaxRate taxRate;
    private final PaymentRepository payments;
    private final EntityManager entityManager;
    private final ExchangeRates exchangeRates;

    public TestSupportController(Audit audit, JdbcTemplate jdbc, InvoiceRepository invoices, TaxRate taxRate,
            PaymentRepository payments, EntityManager entityManager, ExchangeRates exchangeRates) {
        this.exchangeRates = exchangeRates;
        this.payments = payments;
        this.entityManager = entityManager;
        this.audit = audit;
        this.jdbc = jdbc;
        this.invoices = invoices;
        this.taxRate = taxRate;
    }

    /** Truncate all domain tables and clear the audit file so each spec starts clean. */
    @PostMapping("/reset")
    @Transactional
    public void reset() {
        audit.clear();
        jdbc.execute("DELETE FROM app_setting");
        jdbc.execute("DELETE FROM fx_rate");
        jdbc.execute("DELETE FROM payment_allocation");
        jdbc.execute("DELETE FROM client_rate");
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
            if (values.get("lateInterestRate") != null) {
                BigDecimal rate = percent(values.get("lateInterestRate"), "settings.lateInterestRate");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        LateInterestRate.LATE_INTEREST_RATE, rate.toPlainString());
            }
            if (values.get("lateFee") != null) {
                BigDecimal fee = money(values.get("lateFee"), "settings.lateFee");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        LateFee.LATE_FEE, fee.toPlainString());
            }
            if (values.get("minimumCharge") != null) {
                BigDecimal minimum = money(values.get("minimumCharge"), "settings.minimumCharge");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        MINIMUM_CHARGE, minimum.toPlainString());
            }
            if (values.get("usdPer") != null) {
                if (!(values.get("usdPer") instanceof Map<?, ?> rates)) {
                    throw refuse("settings.usdPer must be an object of rates by currency code");
                }
                for (Map.Entry<?, ?> e : rates.entrySet()) {
                    String code = e.getKey().toString();
                    if (Currency.of(code) == null) {
                        throw refuse("unsupported currency in settings.usdPer: " + code);
                    }
                    BigDecimal usdPer;
                    try {
                        usdPer = new BigDecimal(String.valueOf(e.getValue()));
                    } catch (NumberFormatException ex) {
                        throw refuse("settings.usdPer." + code + " must be a number, not " + e.getValue());
                    }
                    if (usdPer.signum() <= 0) {
                        throw refuse("settings.usdPer." + code + " must be more than zero, not " + e.getValue());
                    }
                    jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                            ExchangeRates.USD_PER + code, usdPer.toPlainString());
                }
            }
            if (values.get("regionTaxRates") != null) {
                if (!(values.get("regionTaxRates") instanceof Map<?, ?> rates)) {
                    throw refuse("settings.regionTaxRates must be an object of percentages by region");
                }
                for (Map.Entry<?, ?> e : rates.entrySet()) {
                    String region = e.getKey().toString();
                    if (region.isBlank() || e.getValue() == null) {
                        throw refuse("settings.regionTaxRates needs a region and a rate, not " + region + ": " + e.getValue());
                    }
                    BigDecimal rate;
                    try {
                        rate = new BigDecimal(String.valueOf(e.getValue()));
                    } catch (NumberFormatException ex) {
                        throw refuse("settings.regionTaxRates." + region + " must be a number, not " + e.getValue());
                    }
                    if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
                        throw refuse("settings.regionTaxRates." + region + " must be from 0 to 100, not " + e.getValue());
                    }
                    jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                            TaxRate.REGION_TAX_RATE + region, rate.toPlainString());
                }
            }
            if (values.get("homeRegion") != null) {
                String home = values.get("homeRegion").toString();
                if (home.isBlank()) {
                    throw refuse("settings.homeRegion must name a region");
                }
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)", TaxRate.HOME_REGION, home);
            }
            for (Map<String, Object> rate : rows(values.get("fxRates"), "settings.fxRates")) {
                only(rate, FX_RATE_FIELDS, "settings.fxRates[]");
                if (rate.get("from") == null || rate.get("currency") == null || rate.get("usdPer") == null) {
                    throw refuse("settings.fxRates[] needs from, currency and usdPer");
                }
                Currency currency = Currency.of(rate.get("currency").toString());
                if (currency == null) {
                    throw refuse("unsupported value of settings.fxRates[].currency: " + rate.get("currency"));
                }
                BigDecimal usdPer;
                try {
                    usdPer = new BigDecimal(String.valueOf(rate.get("usdPer")));
                } catch (NumberFormatException ex) {
                    throw refuse("settings.fxRates[].usdPer must be a number, not " + rate.get("usdPer"));
                }
                if (usdPer.signum() <= 0) {
                    throw refuse("settings.fxRates[].usdPer must be more than zero, not " + rate.get("usdPer"));
                }
                exchangeRates.addDated(date(rate.get("from"), "settings.fxRates[].from"), currency, usdPer);
            }
        }
        for (Map<String, Object> row : rows(fixture.get("clients"), "clients")) {
            only(row, CLIENT_FIELDS, "clients[]");
            String currency = row.get("currency") == null ? Currency.USD.name() : row.get("currency").toString();
            if (Currency.of(currency) == null) {
                throw refuse("unsupported value of clients[].currency: " + currency);
            }
            insert("client", row.get("id"), new String[] {"name", "email", "terms_days", "discount_pct", "tax_exempt", "prices_include_tax", "currency", "early_pay_pct", "early_pay_days", "retention_pct", "grace_days", "interest_rate", "region", "tax_number"},
                    text(row, "name"), text(row, "email"), days(row.get("termsDays"), "clients[].termsDays"),
                    percent(row.get("discountPct"), "clients[].discountPct"),
                    flag(row.get("taxExempt"), "clients[].taxExempt"),
                    flag(row.get("pricesIncludeTax"), "clients[].pricesIncludeTax"), currency,
                    percent(row.get("earlyPayPct"), "clients[].earlyPayPct"),
                    days(row.get("earlyPayDays"), "clients[].earlyPayDays"),
                    percent(row.get("retentionPct"), "clients[].retentionPct"),
                    graceDays(row.get("graceDays"), "clients[].graceDays"),
                    interestRate(row.get("interestRate"), "clients[].interestRate"),
                    row.get("region") == null ? null : row.get("region").toString(),
                    row.get("taxNumber") == null ? null : row.get("taxNumber").toString());
            Long clientId = id(row.get("id") == null
                    ? jdbc.queryForObject("SELECT MAX(id) FROM client", Long.class) : row.get("id"));
            for (Map<String, Object> rate : rows(row.get("rates"), "clients[].rates")) {
                only(rate, Set.of("unitType", "unitPrice", "tiers"), "clients[].rates[]");
                if (rate.get("unitType") == null || rate.get("unitType").toString().isBlank()) {
                    throw refuse("clients[].rates[].unitType is required");
                }
                RateTiers tiers = tiers(rate.get("tiers"));
                if ((tiers == null) == (rate.get("unitPrice") == null)) {
                    throw refuse("clients[].rates[] needs either unitPrice or tiers");
                }
                jdbc.update("INSERT INTO client_rate (client_id, unit_type, unit_price, tiers) VALUES (?, ?, ?, ?)", clientId,
                        rate.get("unitType").toString(),
                        tiers != null ? null : cents(rate.get("unitPrice"), "clients[].rates[].unitPrice"),
                        tiers == null ? null : tiers.format());
            }
        }
        for (Map<String, Object> row : rows(fixture.get("invoices"), "invoices")) {
            only(row, INVOICE_FIELDS, "invoices[]");
            String status = row.get("status") == null ? "DRAFT" : row.get("status").toString();
            if (!STATUSES.contains(status)) {
                throw refuse("unsupported value of invoices[].status: " + status);
            }
            if ("CREDIT_NOTE".equals(status) && row.get("creditOf") == null) {
                throw refuse("an invoices[] row with status CREDIT_NOTE needs creditOf");
            }
            // A credit note that has been sent is an issued credit note.
            if ("SENT".equals(status) && row.get("creditOf") != null) {
                status = "CREDIT_NOTE";
            }
            if (row.get("clientId") == null) {
                throw refuse("invoices[].clientId is required");
            }
            Long invoiceId = insert("invoice", row.get("id"),
                    new String[] {"client_id", "status", "issue_date", "due_date", "discount_pct", "discount_amount", "discount_cap", "discount_reason", "manual_due_date", "credit_of", "retention_pct", "retention_cap", "retention_released_on", "retention_released_amount", "instalments", "instalment_rate", "plan_down_pct", "interest_waived_on", "written_off_on", "skipped_instalment"},
                    id(row.get("clientId")), status,
                    row.get("issueDate") == null ? null : date(row.get("issueDate"), "invoices[].issueDate"),
                    row.get("dueDate") == null ? null : date(row.get("dueDate"), "invoices[].dueDate"),
                    percent(row.get("discountPct"), "invoices[].discountPct"),
                    money(row.get("discountAmount"), "invoices[].discountAmount"),
                    row.get("discountCap") == null ? null : money(row.get("discountCap"), "invoices[].discountCap"),
                    row.get("discountReason") == null ? null : row.get("discountReason").toString(),
                    flag(row.get("manualDueDate"), "invoices[].manualDueDate"),
                    row.get("creditOf") == null ? null : id(row.get("creditOf")),
                    percent(row.get("retentionPct"), "invoices[].retentionPct"),
                    row.get("retentionCap") == null ? null : money(row.get("retentionCap"), "invoices[].retentionCap"),
                    row.get("retentionReleasedOn") == null ? null
                            : date(row.get("retentionReleasedOn"), "invoices[].retentionReleasedOn"),
                    row.get("retentionReleasedAmount") == null ? null
                            : money(row.get("retentionReleasedAmount"), "invoices[].retentionReleasedAmount"),
                    instalments(row.get("instalments"), "invoices[].instalments"),
                    instalmentRate(row.get("instalmentRate"), "invoices[].instalmentRate"),
                    planDownPct(row.get("planDownPct"), "invoices[].planDownPct"),
                    row.get("interestWaivedOn") == null ? null
                            : date(row.get("interestWaivedOn"), "invoices[].interestWaivedOn"),
                    row.get("writtenOffOn") == null ? null
                            : date(row.get("writtenOffOn"), "invoices[].writtenOffOn"),
                    skippedInstalment(row.get("skippedInstalment"), "invoices[].skippedInstalment"));
            for (Map<String, Object> line : rows(row.get("lineItems"), "invoices[].lineItems")) {
                only(line, LINE_FIELDS, "invoices[].lineItems[]");
                Object[] price = linePrice(invoiceId, line);
                insert("invoice_line", line.get("id"),
                        new String[] {"invoice_id", "description", "qty", "unit_price", "tiers", "tax_exempt", "discount_pct", "tax_rate", "credit_of_line", "unit_type"},
                        invoiceId, text(line, "description"),
                        cents(line.get("qty"), "invoices[].lineItems[].qty"),
                        price[0], price[1],
                        flag(line.get("taxExempt"), "invoices[].lineItems[].taxExempt"),
                        percent(line.get("discountPct"), "invoices[].lineItems[].discountPct"),
                        line.get("taxRate") == null ? null : percent(line.get("taxRate"), "invoices[].lineItems[].taxRate"),
                        line.get("creditOfLine") == null ? null : id(line.get("creditOfLine")),
                        line.get("unitType") == null ? null : line.get("unitType").toString());
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
            // An amount paid in another currency than the client's is applied converted into the client's.
            Currency clientCurrency = Currency.orUsd(jdbc.queryForList("SELECT currency FROM client WHERE id = ?",
                    String.class, id(row.get("clientId"))).stream().findFirst().orElse(null));
            Currency paidIn = clientCurrency;
            if (row.get("currency") != null) {
                paidIn = Currency.of(row.get("currency").toString());
                if (paidIn == null) {
                    throw refuse("unsupported value of payments[].currency: " + row.get("currency"));
                }
            }
            BigDecimal paidAmount = cents(row.get("amount"), "payments[].amount");
            String method = row.get("method") == null ? PaymentMethod.BANK.name() : row.get("method").toString();
            if (PaymentMethod.of(method) == null) {
                throw refuse("unsupported value of payments[].method: " + method);
            }
            boolean foreign = paidIn != clientCurrency && paidAmount != null;
            Long paymentId = insert("payment", row.get("id"), new String[] {"client_id", "amount", "payment_date", "reference", "reversed", "refunded", "credit_note", "paid_currency", "paid_amount", "method"},
                    id(row.get("clientId")), foreign ? exchangeRates.convert(paidAmount, paidIn, clientCurrency, date(row.get("date"), "payments[].date")) : paidAmount,
                    date(row.get("date"), "payments[].date"),
                    row.get("reference") == null ? null : row.get("reference").toString(),
                    flag(row.get("reversed"), "payments[].reversed"),
                    money(row.get("refunded"), "payments[].refunded"),
                    row.get("creditNote") == null ? null : id(row.get("creditNote")),
                    foreign ? paidIn.name() : null, foreign ? paidAmount : null, method);
            for (Map<String, Object> allocation : rows(row.get("allocations"), "payments[].allocations")) {
                only(allocation, ALLOCATION_FIELDS, "payments[].allocations[]");
                if (allocation.get("invoiceId") == null) {
                    throw refuse("payments[].allocations[].invoiceId is required");
                }
                jdbc.update("INSERT INTO payment_allocation (payment_id, invoice_id, amount, applied_date) VALUES (?, ?, ?, ?)",
                        paymentId, id(allocation.get("invoiceId")),
                        cents(allocation.get("amount"), "payments[].allocations[].amount"),
                        allocation.get("date") == null ? null : date(allocation.get("date"), "payments[].allocations[].date"));
            }
        }
        // An issued credit note keeps its grand total, which comes off what its original owes, then the
        // retention it holds back, in the order they were issued; any rest is the client's credit.
        BigDecimal rate = taxRate.percent();
        for (Long noteId : jdbc.queryForList("SELECT id FROM invoice WHERE status = 'CREDIT_NOTE' ORDER BY id", Long.class)) {
            Invoice note = invoices.findWithLinesById(noteId).orElseThrow();
            BigDecimal total = note.total(rate);
            BigDecimal applied = BigDecimal.ZERO;
            BigDecimal toRetention = BigDecimal.ZERO;
            Invoice original = invoices.findWithLinesById(note.getCreditOf()).orElse(null);
            if (original != null) {
                entityManager.refresh(original);
                BigDecimal paid = payments.paidTo(List.of(original.getId())).stream()
                        .map(r -> (BigDecimal) r[1]).findFirst().orElse(BigDecimal.ZERO);
                BigDecimal[] taken = original.creditTaken(total, paid, rate);
                applied = taken[0];
                toRetention = taken[1];
            }
            jdbc.update("UPDATE invoice SET credit_total = ?, credit_applied = ?, credit_retention = ? WHERE id = ?",
                    total, applied, toRetention, noteId);
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
        Map<String, Object> settings = new LinkedHashMap<>();
        if (!taxRate.isEmpty()) {
            settings.put("taxRate", new BigDecimal(taxRate.get(0)));
        }
        List<String> lateInterestRate = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, LateInterestRate.LATE_INTEREST_RATE);
        if (!lateInterestRate.isEmpty()) {
            settings.put("lateInterestRate", new BigDecimal(lateInterestRate.get(0)));
        }
        List<String> lateFee = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, LateFee.LATE_FEE);
        if (!lateFee.isEmpty()) {
            settings.put("lateFee", new BigDecimal(lateFee.get(0)));
        }
        List<String> minimumCharge = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, MINIMUM_CHARGE);
        if (!minimumCharge.isEmpty()) {
            settings.put("minimumCharge", new BigDecimal(minimumCharge.get(0)));
        }
        Map<String, BigDecimal> regionTaxRates = this.taxRate.regions();
        if (!regionTaxRates.isEmpty()) {
            settings.put("regionTaxRates", regionTaxRates);
        }
        String homeRegion = this.taxRate.homeRegion();
        if (homeRegion != null) {
            settings.put("homeRegion", homeRegion);
        }
        Map<String, BigDecimal> rates = exchangeRates.all();
        if (!rates.isEmpty()) {
            settings.put("usdPer", rates);
        }
        List<Map<String, Object>> fxRates = exchangeRates.dated().stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", r.from().toString());
            row.put("currency", r.currency().name());
            row.put("usdPer", r.usdPer());
            return row;
        }).toList();
        if (!fxRates.isEmpty()) {
            settings.put("fxRates", fxRates);
        }
        if (!settings.isEmpty()) {
            out.put("settings", settings);
        }
        out.put("clients", jdbc.queryForList("SELECT id, name, email, terms_days, discount_pct, tax_exempt, prices_include_tax, currency, early_pay_pct, early_pay_days, retention_pct, grace_days, interest_rate, region, tax_number FROM client ORDER BY id").stream()
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.get("ID"));
                    row.put("name", r.get("NAME"));
                    row.put("email", r.get("EMAIL"));
                    if (r.get("TERMS_DAYS") != null) {
                        row.put("termsDays", r.get("TERMS_DAYS"));
                    }
                    row.put("discountPct", r.get("DISCOUNT_PCT"));
                    row.put("taxExempt", r.get("TAX_EXEMPT"));
                    row.put("pricesIncludeTax", r.get("PRICES_INCLUDE_TAX"));
                    row.put("currency", r.get("CURRENCY"));
                    row.put("earlyPayPct", r.get("EARLY_PAY_PCT"));
                    if (r.get("EARLY_PAY_DAYS") != null) {
                        row.put("earlyPayDays", r.get("EARLY_PAY_DAYS"));
                    }
                    row.put("retentionPct", r.get("RETENTION_PCT"));
                    row.put("graceDays", r.get("GRACE_DAYS"));
                    if (r.get("INTEREST_RATE") != null) {
                        row.put("interestRate", r.get("INTEREST_RATE"));
                    }
                    if (r.get("REGION") != null) {
                        row.put("region", r.get("REGION"));
                    }
                    if (r.get("TAX_NUMBER") != null) {
                        row.put("taxNumber", r.get("TAX_NUMBER"));
                    }
                    List<Map<String, Object>> rateCard = jdbc.queryForList(
                            "SELECT unit_type, unit_price, tiers FROM client_rate WHERE client_id = ? ORDER BY unit_type",
                            r.get("ID")).stream().map(c -> {
                                Map<String, Object> rate = new LinkedHashMap<>();
                                rate.put("unitType", c.get("UNIT_TYPE"));
                                RateTiers tiers = RateTiers.parse((String) c.get("TIERS"));
                                if (tiers == null) {
                                    rate.put("unitPrice", c.get("UNIT_PRICE"));
                                } else {
                                    rate.put("tiers", tiers.bands().stream().map(b -> {
                                        Map<String, Object> band = new LinkedHashMap<>();
                                        if (b.units() != null) {
                                            band.put("units", b.units());
                                        }
                                        band.put("unitPrice", b.unitPrice());
                                        return band;
                                    }).toList());
                                }
                                return rate;
                            }).toList();
                    if (!rateCard.isEmpty()) {
                        row.put("rates", rateCard);
                    }
                    return row;
                }).toList());
        out.put("invoices", jdbc.queryForList(
                "SELECT id, client_id, status, issue_date, due_date, discount_pct, discount_amount, discount_cap, discount_reason, manual_due_date, credit_of, retention_pct, retention_cap, retention_released_on, retention_released_amount, instalments, instalment_rate, plan_down_pct, interest_waived_on, written_off_on, skipped_instalment FROM invoice ORDER BY id").stream()
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.get("ID"));
                    row.put("clientId", r.get("CLIENT_ID"));
                    // An issued credit note is seeded as a sent one.
                    row.put("status", "CREDIT_NOTE".equals(r.get("STATUS")) ? "SENT" : r.get("STATUS"));
                    row.put("issueDate", r.get("ISSUE_DATE") == null ? null : r.get("ISSUE_DATE").toString());
                    row.put("dueDate", r.get("DUE_DATE") == null ? null : r.get("DUE_DATE").toString());
                    row.put("discountPct", r.get("DISCOUNT_PCT"));
                    row.put("discountAmount", r.get("DISCOUNT_AMOUNT"));
                    row.put("manualDueDate", r.get("MANUAL_DUE_DATE"));
                    if (r.get("DISCOUNT_CAP") != null) {
                        row.put("discountCap", r.get("DISCOUNT_CAP"));
                    }
                    row.put("retentionPct", r.get("RETENTION_PCT"));
                    if (r.get("RETENTION_CAP") != null) {
                        row.put("retentionCap", r.get("RETENTION_CAP"));
                    }
                    if (r.get("RETENTION_RELEASED_ON") != null) {
                        row.put("retentionReleasedOn", r.get("RETENTION_RELEASED_ON").toString());
                    }
                    if (r.get("RETENTION_RELEASED_AMOUNT") != null) {
                        row.put("retentionReleasedAmount", r.get("RETENTION_RELEASED_AMOUNT"));
                    }
                    if (r.get("INTEREST_WAIVED_ON") != null) {
                        row.put("interestWaivedOn", r.get("INTEREST_WAIVED_ON").toString());
                    }
                    if (r.get("WRITTEN_OFF_ON") != null) {
                        row.put("writtenOffOn", r.get("WRITTEN_OFF_ON").toString());
                    }
                    if (r.get("INSTALMENTS") != null) {
                        row.put("instalments", r.get("INSTALMENTS"));
                    }
                    row.put("instalmentRate", r.get("INSTALMENT_RATE"));
                    row.put("planDownPct", r.get("PLAN_DOWN_PCT"));
                    if (r.get("SKIPPED_INSTALMENT") != null) {
                        row.put("skippedInstalment", r.get("SKIPPED_INSTALMENT"));
                    }
                    if (r.get("CREDIT_OF") != null) {
                        row.put("creditOf", r.get("CREDIT_OF"));
                    }
                    if (r.get("DISCOUNT_REASON") != null) {
                        row.put("discountReason", r.get("DISCOUNT_REASON"));
                    }
                    row.put("lineItems", jdbc.queryForList(
                            "SELECT id, description, qty, unit_type, unit_price, tax_exempt, discount_pct, tax_rate, credit_of_line FROM invoice_line WHERE invoice_id = ? ORDER BY id",
                            r.get("ID")).stream().map(l -> {
                                Map<String, Object> line = new LinkedHashMap<>();
                                line.put("id", l.get("ID"));
                                line.put("description", l.get("DESCRIPTION"));
                                line.put("qty", l.get("QTY"));
                                if (l.get("UNIT_TYPE") != null) {
                                    line.put("unitType", l.get("UNIT_TYPE"));
                                }
                                line.put("unitPrice", l.get("UNIT_PRICE"));
                                line.put("taxExempt", l.get("TAX_EXEMPT"));
                                line.put("discountPct", l.get("DISCOUNT_PCT"));
                                if (l.get("TAX_RATE") != null) {
                                    line.put("taxRate", l.get("TAX_RATE"));
                                }
                                if (l.get("CREDIT_OF_LINE") != null) {
                                    line.put("creditOfLine", l.get("CREDIT_OF_LINE"));
                                }
                                return line;
                            }).toList());
                    return row;
                }).toList());
        out.put("payments", jdbc.queryForList("SELECT p.id, p.client_id, p.amount, p.payment_date, p.reference, p.reversed, p.refunded, p.credit_note, p.method, "
                + "p.paid_currency, p.paid_amount, c.currency FROM payment p LEFT JOIN client c ON c.id = p.client_id ORDER BY p.id")
                .stream().map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.get("ID"));
                    row.put("clientId", r.get("CLIENT_ID"));
                    row.put("amount", r.get("PAID_CURRENCY") == null ? r.get("AMOUNT") : r.get("PAID_AMOUNT"));
                    row.put("currency", r.get("PAID_CURRENCY") == null ? r.get("CURRENCY") : r.get("PAID_CURRENCY"));
                    row.put("date", r.get("PAYMENT_DATE").toString());
                    row.put("reference", r.get("REFERENCE"));
                    row.put("reversed", r.get("REVERSED"));
                    row.put("refunded", r.get("REFUNDED"));
                    row.put("method", r.get("METHOD"));
                    if (r.get("CREDIT_NOTE") != null) {
                        row.put("creditNote", r.get("CREDIT_NOTE"));
                    }
                    row.put("allocations", jdbc.queryForList(
                            "SELECT invoice_id, amount, applied_date FROM payment_allocation WHERE payment_id = ? ORDER BY invoice_id",
                            r.get("ID")).stream().map(a -> {
                                Map<String, Object> allocation = new LinkedHashMap<>();
                                allocation.put("invoiceId", a.get("INVOICE_ID"));
                                allocation.put("amount", a.get("AMOUNT"));
                                if (a.get("APPLIED_DATE") != null) {
                                    allocation.put("date", a.get("APPLIED_DATE").toString());
                                }
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

    /**
     * A seeded line's unit price and the tiers it is priced in (null for a single price): the price
     * given, or else its invoice's client's rate for the line's unit type — a single price, or tiers
     * whose first band's price is kept as the unit price; refused when neither is there.
     */
    private Object[] linePrice(Long invoiceId, Map<String, Object> line) {
        if (line.get("unitPrice") != null || line.get("unitType") == null) {
            return new Object[] {cents(line.get("unitPrice"), "invoices[].lineItems[].unitPrice"), null};
        }
        List<Map<String, Object>> rate = jdbc.queryForList("SELECT r.unit_price, r.tiers FROM client_rate r JOIN invoice i ON i.client_id = r.client_id "
                + "WHERE i.id = ? AND LOWER(r.unit_type) = LOWER(?)", invoiceId,
                line.get("unitType").toString().trim());
        if (rate.isEmpty()) {
            throw refuse("invoices[].lineItems[].unitPrice is required: the client has no rate for unitType "
                    + line.get("unitType"));
        }
        RateTiers tiers = RateTiers.parse((String) rate.get(0).get("TIERS"));
        if (tiers != null) {
            return new Object[] {tiers.firstPrice(), tiers.format()};
        }
        return new Object[] {rate.get(0).get("UNIT_PRICE"), null};
    }

    /**
     * A rate card entry's tiers: bands of {@code units} each with its {@code unitPrice}, the last with
     * no units covering everything above; null when left out.
     */
    private static RateTiers tiers(Object value) {
        if (value == null) {
            return null;
        }
        List<Map<String, Object>> rows = rows(value, "clients[].rates[].tiers");
        if (rows.isEmpty()) {
            throw refuse("clients[].rates[].tiers needs at least one band");
        }
        List<RateTiers.Band> bands = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> band = rows.get(i);
            only(band, Set.of("units", "unitPrice"), "clients[].rates[].tiers[]");
            boolean last = i == rows.size() - 1;
            if (!last && band.get("units") == null) {
                throw refuse("clients[].rates[].tiers[].units is required on every band but the last");
            }
            if (last && band.get("units") != null) {
                throw refuse("clients[].rates[].tiers[].units must be left out of the last band, which covers the rest");
            }
            BigDecimal units = last ? null : cents(band.get("units"), "clients[].rates[].tiers[].units");
            if (units != null && units.signum() <= 0) {
                throw refuse("clients[].rates[].tiers[].units must be more than zero, not " + band.get("units"));
            }
            if (band.get("unitPrice") == null) {
                throw refuse("clients[].rates[].tiers[].unitPrice is required");
            }
            bands.add(new RateTiers.Band(units, cents(band.get("unitPrice"), "clients[].rates[].tiers[].unitPrice")));
        }
        return new RateTiers(bands);
    }

    /** A whole number of days, not negative; null when left out. */
    private static Integer days(Object value, String field) {
        if (value == null) {
            return null;
        }
        try {
            int days = new BigDecimal(value.toString()).intValueExact();
            if (days < 0) {
                throw refuse(field + " cannot be negative, not " + value);
            }
            return days;
        } catch (NumberFormatException | ArithmeticException e) {
            throw refuse(field + " must be a whole number of days, not " + value);
        }
    }

    /** A grace period of 0 to 30 whole days; the standard week when left out. */
    private static int graceDays(Object value, String field) {
        Integer days = days(value, field);
        if (days == null) {
            return Invoice.GRACE_DAYS;
        }
        if (days > Client.MAX_GRACE_DAYS) {
            throw refuse(field + " must be from 0 to " + Client.MAX_GRACE_DAYS + " days, not " + value);
        }
        return days;
    }

    /** A client's own late-interest rate, 0 to 30 percent to the hundredth; null (the workspace's) when left out. */
    private static BigDecimal interestRate(Object value, String field) {
        if (value == null) {
            return null;
        }
        BigDecimal rate = cents(value, field);
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(Client.MAX_INTEREST_RATE)) > 0) {
            throw refuse(field + " must be from 0 to " + Client.MAX_INTEREST_RATE + ", not " + value);
        }
        return rate;
    }

    /** A whole number of instalments from 2 to 24; null (no plan) when left out. */
    private static Integer instalments(Object value, String field) {
        if (value == null) {
            return null;
        }
        try {
            int count = new BigDecimal(value.toString()).intValueExact();
            if (count < Invoice.MIN_INSTALMENTS || count > Invoice.MAX_INSTALMENTS) {
                throw refuse(field + " must be from " + Invoice.MIN_INSTALMENTS + " to " + Invoice.MAX_INSTALMENTS
                        + ", not " + value);
            }
            return count;
        } catch (NumberFormatException | ArithmeticException e) {
            throw refuse(field + " must be a whole number, not " + value);
        }
    }

    /** The number of a plan's skipped instalment, from 1; null (none skipped) when left out. */
    private static Integer skippedInstalment(Object value, String field) {
        if (value == null) {
            return null;
        }
        try {
            int number = new BigDecimal(value.toString()).intValueExact();
            if (number < 1 || number > Invoice.MAX_INSTALMENTS) {
                throw refuse(field + " must be from 1 to " + Invoice.MAX_INSTALMENTS + ", not " + value);
            }
            return number;
        } catch (NumberFormatException | ArithmeticException e) {
            throw refuse(field + " must be a whole number, not " + value);
        }
    }

    /** An annual interest rate from 0 to 30 with at most two decimals; none given is zero. */
    private static BigDecimal instalmentRate(Object value, String field) {
        BigDecimal rate = percent(value, field);
        if (rate.compareTo(Invoice.MAX_INSTALMENT_RATE) > 0) {
            throw refuse(field + " must be from 0 to " + Invoice.MAX_INSTALMENT_RATE + ", not " + value);
        }
        return rate;
    }

    /** A plan's down payment percentage from 0 to 50 with at most two decimals; none given is zero. */
    private static BigDecimal planDownPct(Object value, String field) {
        BigDecimal pct = percent(value, field);
        if (pct.compareTo(Invoice.MAX_PLAN_DOWN_PCT) > 0) {
            throw refuse(field + " must be from 0 to " + Invoice.MAX_PLAN_DOWN_PCT + ", not " + value);
        }
        return pct;
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

    /** An optional amount of money, not negative and to the cent; 0 when left out. */
    private static BigDecimal money(Object value, String field) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal number = cents(value, field);
        if (number.signum() < 0) {
            throw refuse(field + " cannot be negative, not " + value);
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
