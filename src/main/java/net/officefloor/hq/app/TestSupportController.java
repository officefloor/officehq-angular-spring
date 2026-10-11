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
    private static final Set<String> SETTINGS_FIELDS = Set.of("taxRate", "usdPer", "fxRates", "lateInterestRate", "regionTaxRates", "homeRegion", "minimumCharge", "cardSurchargePct", "rates", "taxRateChanges", "regionMaxInterest", "volumeDiscounts");
    private static final Set<String> VOLUME_DISCOUNT_FIELDS = Set.of("over", "pct");
    private static final Set<String> TAX_RATE_CHANGE_FIELDS = Set.of("region", "from", "rate");
    /** The setting holding the workspace's minimum charge in US dollars (see Invoice#minimumCharge). */
    private static final String MINIMUM_CHARGE = "minimum_charge";
    private static final Set<String> FX_RATE_FIELDS = Set.of("from", "currency", "usdPer");
    private static final Set<String> CLIENT_FIELDS = Set.of("id", "name", "email", "termsDays", "discountPct", "taxExempt", "pricesIncludeTax", "currency", "earlyPayPct", "earlyPayDays", "retentionPct", "graceDays", "interestRate", "region", "taxNumber", "rates", "withholdingPct", "minimumCharge");
    private static final Set<String> INVOICE_FIELDS =
            Set.of("id", "clientId", "status", "issueDate", "dueDate", "discountPct", "discountAmount", "discountCap", "discountReason", "manualDueDate", "creditOf", "retentionPct", "retentionCap", "retentionReleasedOn", "retentionReleasedAmount", "instalments", "instalmentRate", "planDownPct", "interestWaivedOn", "writtenOffOn", "skippedInstalment", "surchargePct", "lineItems");
    private static final Set<String> LINE_FIELDS = Set.of("id", "description", "qty", "unitPrice", "taxExempt", "discountPct", "taxRate", "creditOfLine", "unitType", "overtime");
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

    private final CardSurcharge cardSurcharge;

    private final PriceList priceList;

    private final Today today;

    private final LateInterestRate lateInterestRate;

    private final VolumeDiscounts volumeDiscounts;

    public TestSupportController(Audit audit, JdbcTemplate jdbc, InvoiceRepository invoices, TaxRate taxRate,
            PaymentRepository payments, EntityManager entityManager, ExchangeRates exchangeRates,
            CardSurcharge cardSurcharge, PriceList priceList, Today today, LateInterestRate lateInterestRate,
            VolumeDiscounts volumeDiscounts) {
        this.volumeDiscounts = volumeDiscounts;
        this.lateInterestRate = lateInterestRate;
        this.today = today;
        this.priceList = priceList;
        this.cardSurcharge = cardSurcharge;
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
        jdbc.execute("DELETE FROM volume_discount");
        jdbc.execute("DELETE FROM tax_rate_change");
        jdbc.execute("DELETE FROM payment_allocation");
        jdbc.execute("DELETE FROM client_rate");
        jdbc.execute("DELETE FROM price_list_rate");
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
            if (values.get("minimumCharge") != null) {
                BigDecimal minimum = money(values.get("minimumCharge"), "settings.minimumCharge");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        MINIMUM_CHARGE, minimum.toPlainString());
            }
            if (values.get("cardSurchargePct") != null) {
                BigDecimal rate = percent(values.get("cardSurchargePct"), "settings.cardSurchargePct");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        CardSurcharge.CARD_SURCHARGE_PCT, rate.toPlainString());
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
            if (values.get("regionMaxInterest") != null) {
                if (!(values.get("regionMaxInterest") instanceof Map<?, ?> caps)) {
                    throw refuse("settings.regionMaxInterest must be an object of percentages by region");
                }
                for (Map.Entry<?, ?> e : caps.entrySet()) {
                    String region = e.getKey().toString();
                    if (region.isBlank() || e.getValue() == null) {
                        throw refuse("settings.regionMaxInterest needs a region and a rate, not " + region + ": " + e.getValue());
                    }
                    lateInterestRate.setRegionMaximum(region,
                            percent(e.getValue(), "settings.regionMaxInterest." + region));
                }
            }
            if (values.get("homeRegion") != null) {
                String home = values.get("homeRegion").toString();
                if (home.isBlank()) {
                    throw refuse("settings.homeRegion must name a region");
                }
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)", TaxRate.HOME_REGION, home);
            }
            for (Map<String, Object> rate : rows(values.get("rates"), "settings.rates")) {
                only(rate, Set.of("unitType", "unitPrice", "tiers"), "settings.rates[]");
                if (rate.get("unitType") == null || rate.get("unitType").toString().isBlank()) {
                    throw refuse("settings.rates[].unitType is required");
                }
                RateTiers tiers = tiers(rate.get("tiers"), "settings.rates[].tiers");
                if ((tiers == null) == (rate.get("unitPrice") == null)) {
                    throw refuse("settings.rates[] needs either unitPrice or tiers");
                }
                priceList.add(rate.get("unitType").toString(),
                        tiers != null ? null : cents(rate.get("unitPrice"), "settings.rates[].unitPrice"), tiers);
            }
            for (Map<String, Object> change : rows(values.get("taxRateChanges"), "settings.taxRateChanges")) {
                only(change, TAX_RATE_CHANGE_FIELDS, "settings.taxRateChanges[]");
                if (change.get("region") == null || change.get("region").toString().isBlank()
                        || change.get("from") == null || change.get("rate") == null) {
                    throw refuse("settings.taxRateChanges[] needs region, from and rate");
                }
                BigDecimal rate;
                try {
                    rate = new BigDecimal(String.valueOf(change.get("rate")));
                } catch (NumberFormatException ex) {
                    throw refuse("settings.taxRateChanges[].rate must be a number, not " + change.get("rate"));
                }
                if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw refuse("settings.taxRateChanges[].rate must be from 0 to 100, not " + change.get("rate"));
                }
                taxRate.addChange(change.get("region").toString(), date(change.get("from"), "settings.taxRateChanges[].from"), rate);
            }
            for (Map<String, Object> threshold : rows(values.get("volumeDiscounts"), "settings.volumeDiscounts")) {
                only(threshold, VOLUME_DISCOUNT_FIELDS, "settings.volumeDiscounts[]");
                if (threshold.get("over") == null || threshold.get("pct") == null) {
                    throw refuse("settings.volumeDiscounts[] needs over and pct");
                }
                volumeDiscounts.add(money(threshold.get("over"), "settings.volumeDiscounts[].over"),
                        percent(threshold.get("pct"), "settings.volumeDiscounts[].pct"));
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
            insert("client", row.get("id"), new String[] {"name", "email", "terms_days", "discount_pct", "tax_exempt", "prices_include_tax", "currency", "early_pay_pct", "early_pay_days", "retention_pct", "grace_days", "interest_rate", "region", "tax_number", "withholding_pct", "minimum_charge"},
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
                    row.get("taxNumber") == null ? null : row.get("taxNumber").toString(),
                    percent(row.get("withholdingPct"), "clients[].withholdingPct"),
                    row.get("minimumCharge") == null ? null : money(row.get("minimumCharge"), "clients[].minimumCharge"));
            Long clientId = id(row.get("id") == null
                    ? jdbc.queryForObject("SELECT MAX(id) FROM client", Long.class) : row.get("id"));
            for (Map<String, Object> rate : rows(row.get("rates"), "clients[].rates")) {
                only(rate, Set.of("unitType", "unitPrice", "tiers", "increment", "minUnits", "taxRate"), "clients[].rates[]");
                if (rate.get("unitType") == null || rate.get("unitType").toString().isBlank()) {
                    throw refuse("clients[].rates[].unitType is required");
                }
                RateTiers tiers = tiers(rate.get("tiers"), "clients[].rates[].tiers");
                if ((tiers == null) == (rate.get("unitPrice") == null)) {
                    throw refuse("clients[].rates[] needs either unitPrice or tiers");
                }
                BigDecimal increment = rate.get("increment") == null ? null
                        : cents(rate.get("increment"), "clients[].rates[].increment");
                if (increment != null && increment.signum() <= 0) {
                    throw refuse("clients[].rates[].increment must be more than zero, not " + rate.get("increment"));
                }
                BigDecimal minUnits = rate.get("minUnits") == null ? null
                        : cents(rate.get("minUnits"), "clients[].rates[].minUnits");
                if (minUnits != null && minUnits.signum() <= 0) {
                    throw refuse("clients[].rates[].minUnits must be more than zero, not " + rate.get("minUnits"));
                }
                jdbc.update("INSERT INTO client_rate (client_id, unit_type, unit_price, tiers, increment, min_units, tax_rate) VALUES (?, ?, ?, ?, ?, ?, ?)", clientId,
                        rate.get("unitType").toString(),
                        tiers != null ? null : cents(rate.get("unitPrice"), "clients[].rates[].unitPrice"),
                        tiers == null ? null : tiers.format(), increment, minUnits,
                        rate.get("taxRate") == null ? null : percent(rate.get("taxRate"), "clients[].rates[].taxRate"));
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
                    new String[] {"client_id", "status", "issue_date", "due_date", "discount_pct", "discount_amount", "discount_cap", "discount_reason", "manual_due_date", "credit_of", "retention_pct", "retention_cap", "retention_released_on", "retention_released_amount", "instalments", "instalment_rate", "plan_down_pct", "interest_waived_on", "written_off_on", "skipped_instalment", "surcharge_pct"},
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
                    skippedInstalment(row.get("skippedInstalment"), "invoices[].skippedInstalment"),
                    surchargePct(row.get("surchargePct"), "invoices[].surchargePct"));
            for (Map<String, Object> line : rows(row.get("lineItems"), "invoices[].lineItems")) {
                only(line, LINE_FIELDS, "invoices[].lineItems[]");
                Object[] price = linePrice(invoiceId, line);
                insert("invoice_line", line.get("id"),
                        new String[] {"invoice_id", "description", "qty", "unit_price", "tiers", "tax_exempt", "discount_pct", "tax_rate", "credit_of_line", "unit_type", "billing_increment", "billing_min_units", "overtime", "card_priced"},
                        invoiceId, text(line, "description"),
                        cents(line.get("qty"), "invoices[].lineItems[].qty"),
                        price[0], price[1],
                        flag(line.get("taxExempt"), "invoices[].lineItems[].taxExempt"),
                        percent(line.get("discountPct"), "invoices[].lineItems[].discountPct"),
                        line.get("taxRate") == null ? null : percent(line.get("taxRate"), "invoices[].lineItems[].taxRate"),
                        line.get("creditOfLine") == null ? null : id(line.get("creditOfLine")),
                        line.get("unitType") == null ? null : line.get("unitType").toString(),
                        lineRate(invoiceId, line, "increment"), lineRate(invoiceId, line, "min_units"),
                        flag(line.get("overtime"), "invoices[].lineItems[].overtime"),
                        price[2]);
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
            BigDecimal amount = foreign ? exchangeRates.convert(paidAmount, paidIn, clientCurrency, date(row.get("date"), "payments[].date")) : paidAmount;
            // Part of a card payment is the workspace's card surcharge, as when one is recorded.
            BigDecimal surcharge = PaymentMethod.CARD.name().equals(method) && amount != null
                    ? cardSurcharge.of(amount, clientCurrency.scale()) : BigDecimal.ZERO;
            Long paymentId = insert("payment", row.get("id"), new String[] {"client_id", "amount", "payment_date", "reference", "reversed", "refunded", "credit_note", "paid_currency", "paid_amount", "method", "surcharge"},
                    id(row.get("clientId")), amount,
                    date(row.get("date"), "payments[].date"),
                    row.get("reference") == null ? null : row.get("reference").toString(),
                    flag(row.get("reversed"), "payments[].reversed"),
                    money(row.get("refunded"), "payments[].refunded"),
                    row.get("creditNote") == null ? null : id(row.get("creditNote")),
                    foreign ? paidIn.name() : null, foreign ? paidAmount : null, method, surcharge);
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
        List<String> cardSurchargePct = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, CardSurcharge.CARD_SURCHARGE_PCT);
        if (!cardSurchargePct.isEmpty()) {
            settings.put("cardSurchargePct", new BigDecimal(cardSurchargePct.get(0)));
        }
        List<String> minimumCharge = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, MINIMUM_CHARGE);
        if (!minimumCharge.isEmpty()) {
            settings.put("minimumCharge", new BigDecimal(minimumCharge.get(0)));
        }
        Map<String, BigDecimal> regionMaxInterest = this.lateInterestRate.regionMaximums();
        if (!regionMaxInterest.isEmpty()) {
            settings.put("regionMaxInterest", regionMaxInterest);
        }
        Map<String, BigDecimal> regionTaxRates = this.taxRate.regions();
        if (!regionTaxRates.isEmpty()) {
            settings.put("regionTaxRates", regionTaxRates);
        }
        List<Map<String, Object>> taxRateChanges = this.taxRate.changes().stream().map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("region", c.region());
            row.put("from", c.from().toString());
            row.put("rate", c.rate());
            return row;
        }).toList();
        if (!taxRateChanges.isEmpty()) {
            settings.put("taxRateChanges", taxRateChanges);
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
        List<Map<String, Object>> volumeDiscountRows = volumeDiscounts.all().stream().map(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("over", t.over());
            row.put("pct", t.pct());
            return row;
        }).toList();
        if (!volumeDiscountRows.isEmpty()) {
            settings.put("volumeDiscounts", volumeDiscountRows);
        }
        List<Map<String, Object>> priceListRates = priceList.all().stream()
                .map(e -> rate(e.unitType(), e.unitPrice(), e.tiers())).toList();
        if (!priceListRates.isEmpty()) {
            settings.put("rates", priceListRates);
        }
        if (!settings.isEmpty()) {
            out.put("settings", settings);
        }
        out.put("clients", jdbc.queryForList("SELECT id, name, email, terms_days, discount_pct, tax_exempt, prices_include_tax, currency, early_pay_pct, early_pay_days, retention_pct, grace_days, interest_rate, region, tax_number, withholding_pct, minimum_charge FROM client ORDER BY id").stream()
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
                    row.put("withholdingPct", r.get("WITHHOLDING_PCT"));
                    if (r.get("MINIMUM_CHARGE") != null) {
                        row.put("minimumCharge", r.get("MINIMUM_CHARGE"));
                    }
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
                            "SELECT unit_type, unit_price, tiers, increment, min_units, tax_rate FROM client_rate WHERE client_id = ? ORDER BY unit_type",
                            r.get("ID")).stream().map(c -> {
                                Map<String, Object> entry = rate((String) c.get("UNIT_TYPE"), (BigDecimal) c.get("UNIT_PRICE"),
                                        RateTiers.parse((String) c.get("TIERS")));
                                if (c.get("INCREMENT") != null) {
                                    entry.put("increment", c.get("INCREMENT"));
                                }
                                if (c.get("MIN_UNITS") != null) {
                                    entry.put("minUnits", c.get("MIN_UNITS"));
                                }
                                if (c.get("TAX_RATE") != null) {
                                    entry.put("taxRate", c.get("TAX_RATE"));
                                }
                                return entry;
                            }).toList();
                    if (!rateCard.isEmpty()) {
                        row.put("rates", rateCard);
                    }
                    return row;
                }).toList());
        out.put("invoices", jdbc.queryForList(
                "SELECT id, client_id, status, issue_date, due_date, discount_pct, discount_amount, discount_cap, discount_reason, manual_due_date, credit_of, retention_pct, retention_cap, retention_released_on, retention_released_amount, instalments, instalment_rate, plan_down_pct, interest_waived_on, written_off_on, skipped_instalment, surcharge_pct FROM invoice ORDER BY id").stream()
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
                    row.put("surchargePct", r.get("SURCHARGE_PCT"));
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
                            "SELECT id, description, qty, unit_type, unit_price, tax_exempt, discount_pct, tax_rate, credit_of_line, overtime FROM invoice_line WHERE invoice_id = ? ORDER BY id",
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
                                line.put("overtime", l.get("OVERTIME"));
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
     * whose first band's price is kept as the unit price; refused when neither is there — and whether
     * the line is priced from the card.
     */
    private Object[] linePrice(Long invoiceId, Map<String, Object> line) {
        if (line.get("unitPrice") != null || line.get("unitType") == null) {
            return new Object[] {cents(line.get("unitPrice"), "invoices[].lineItems[].unitPrice"), null, false};
        }
        List<Map<String, Object>> rate = jdbc.queryForList("SELECT r.unit_price, r.tiers FROM client_rate r JOIN invoice i ON i.client_id = r.client_id "
                + "WHERE i.id = ? AND LOWER(r.unit_type) = LOWER(?)", invoiceId,
                line.get("unitType").toString().trim());
        if (rate.isEmpty()) {
            Map<String, Object> invoice = jdbc.queryForMap("SELECT i.issue_date, c.currency FROM invoice i JOIN client c ON c.id = i.client_id WHERE i.id = ?", invoiceId);
            LocalDate on = invoice.get("ISSUE_DATE") == null ? today.date()
                    : ((java.sql.Date) invoice.get("ISSUE_DATE")).toLocalDate();
            PriceList.Converted listed = priceList.priceFor(line.get("unitType").toString(),
                    Currency.orUsd((String) invoice.get("CURRENCY")), on);
            if (listed == null) {
                throw refuse("invoices[].lineItems[].unitPrice is required: neither the client's rate card nor the price list has unitType "
                        + line.get("unitType"));
            }
            return new Object[] {listed.unitPrice(), listed.tiers(), false};
        }
        // A draft's line priced from the card follows it; a sent invoice's has its price fixed.
        boolean draft = "DRAFT".equals(jdbc.queryForObject("SELECT status FROM invoice WHERE id = ?", String.class, invoiceId));
        RateTiers tiers = RateTiers.parse((String) rate.get(0).get("TIERS"));
        if (tiers != null) {
            return new Object[] {tiers.firstPrice(), tiers.format(), draft};
        }
        return new Object[] {rate.get(0).get("UNIT_PRICE"), null, draft};
    }

    /** A seeded line's billing increment or minimum units: its invoice's client's given column for the line's unit type; null for none. */
    private BigDecimal lineRate(Long invoiceId, Map<String, Object> line, String column) {
        if (line.get("unitType") == null) {
            return null;
        }
        List<BigDecimal> found = jdbc.queryForList("SELECT r." + column + " FROM client_rate r JOIN invoice i ON i.client_id = r.client_id "
                + "WHERE i.id = ? AND LOWER(r.unit_type) = LOWER(?)", BigDecimal.class, invoiceId,
                line.get("unitType").toString().trim());
        return found.isEmpty() ? null : found.get(0);
    }

    /** A rate card or price list entry as read back: its unit type and a single unitPrice or tiers. */
    private static Map<String, Object> rate(String unitType, BigDecimal unitPrice, RateTiers tiers) {
        Map<String, Object> rate = new LinkedHashMap<>();
        rate.put("unitType", unitType);
        if (tiers == null) {
            rate.put("unitPrice", unitPrice);
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
    }

    /**
     * A rate card or price list entry's tiers: bands of {@code units} each with its {@code unitPrice}, the last with
     * no units covering everything above; null when left out.
     */
    private static RateTiers tiers(Object value, String field) {
        if (value == null) {
            return null;
        }
        List<Map<String, Object>> rows = rows(value, field);
        if (rows.isEmpty()) {
            throw refuse(field + " needs at least one band");
        }
        List<RateTiers.Band> bands = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> band = rows.get(i);
            only(band, Set.of("units", "unitPrice"), field + "[]");
            boolean last = i == rows.size() - 1;
            if (!last && band.get("units") == null) {
                throw refuse(field + "[].units is required on every band but the last");
            }
            if (last && band.get("units") != null) {
                throw refuse(field + "[].units must be left out of the last band, which covers the rest");
            }
            BigDecimal units = last ? null : cents(band.get("units"), field + "[].units");
            if (units != null && units.signum() <= 0) {
                throw refuse(field + "[].units must be more than zero, not " + band.get("units"));
            }
            if (band.get("unitPrice") == null) {
                throw refuse(field + "[].unitPrice is required");
            }
            bands.add(new RateTiers.Band(units, cents(band.get("unitPrice"), field + "[].unitPrice")));
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

    /** An optional surcharge percentage, 0 to {@link Invoice#MAX_SURCHARGE_PCT}; 0 when left out. */
    private static BigDecimal surchargePct(Object value, String field) {
        BigDecimal number = percent(value, field);
        if (number.compareTo(Invoice.MAX_SURCHARGE_PCT) > 0) {
            throw refuse(field + " must be from 0 to " + Invoice.MAX_SURCHARGE_PCT + ", not " + value);
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
