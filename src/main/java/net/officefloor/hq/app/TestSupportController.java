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
import net.officefloor.hq.app.clients.ClientTier;
import net.officefloor.hq.app.clients.Currency;
import net.officefloor.hq.app.clients.RateDiscount;
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
    private static final Set<String> SETTINGS_FIELDS = Set.of("taxRate", "usdPer", "fxRates", "lateInterestRate", "regionTaxRates", "homeRegion", "minimumCharge", "cardSurchargePct", "cardSurchargeCap", "fxFeePct", "rates", "taxRateChanges", "regionMaxInterest", "volumeDiscounts", "interestThreshold", "regionGraceDays", "regionTermsDays", "withholdingThreshold", "regionRoundDown", "approvalLimit", "tierApprovalLimits", "duplicateDays", "holidays");
    private static final Set<String> VOLUME_DISCOUNT_FIELDS = Set.of("over", "pct");
    private static final Set<String> TAX_RATE_CHANGE_FIELDS = Set.of("region", "from", "rate");
    /** The setting holding the workspace's minimum charge in US dollars (see Invoice#minimumCharge). */
    private static final String MINIMUM_CHARGE = "minimum_charge";

    private static final String INTEREST_THRESHOLD = "interest_threshold";
    /** The setting holding the workspace's withholding threshold in US dollars (see Invoice#withholding). */
    private static final String WITHHOLDING_THRESHOLD = "withholding_threshold";
    /** The setting holding the workspace's approval limit in US dollars (see Invoice#approval). */
    private static final String APPROVAL_LIMIT = "approval_limit";
    private static final Set<String> FX_RATE_FIELDS = Set.of("from", "currency", "usdPer");
    private static final Set<String> CLIENT_FIELDS = Set.of("id", "name", "email", "termsDays", "discountPct", "taxExempt", "pricesIncludeTax", "currency", "earlyPayPct", "earlyPayDays", "graceDays", "interestRate", "region", "taxNumber", "rates", "withholdingPct", "minimumCharge", "rateDiscountPct", "taxExemptUntil", "creditLimit", "onHold", "tier", "upliftPct");
    private static final Set<String> INVOICE_FIELDS =
            Set.of("id", "clientId", "status", "issueDate", "dueDate", "discountPct", "discountAmount", "discountCap", "discountReason", "manualDueDate", "creditOf", "interestWaivedOn", "writtenOffOn", "surchargePct", "supplyRegion", "approvedTotal", "approvedOn", "approvals", "recurring", "recurringFrom", "recurringSince", "recurUntil", "disputedOn", "disputeResolvedOn", "disputeCredit", "poNumber", "claimFrom", "sentTotal", "sentTax", "rebilledOn", "replaces", "adjustmentOf", "promiseAmount", "promiseDate", "promisedOn", "reminders", "lineItems", "milestones");
    private static final Set<String> MILESTONE_FIELDS = Set.of("id", "description", "pct", "reachedOn", "disputedOn");
    private static final Set<String> LINE_FIELDS = Set.of("id", "description", "qty", "unitPrice", "taxExempt", "discountPct", "taxRate", "creditOfLine", "unitType", "overtime", "periodStart", "periodEnd", "disputed", "percentComplete", "billedPct", "interestOf");
    private static final Set<String> PAYMENT_FIELDS = Set.of("id", "clientId", "amount", "date", "reference", "reversed", "refunded", "creditNote", "heldFor", "allocations", "currency", "method", "remittance", "deposit");
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

    private final FxFee fxFee;

    private final PriceList priceList;

    private final Today today;

    private final LateInterestRate lateInterestRate;
    private final Holidays holidays;

    private final VolumeDiscounts volumeDiscounts;

    private final RegionGrace regionGrace;

    private final RegionTerms regionTerms;

    public TestSupportController(Audit audit, JdbcTemplate jdbc, InvoiceRepository invoices, TaxRate taxRate,
            PaymentRepository payments, EntityManager entityManager, ExchangeRates exchangeRates,
            CardSurcharge cardSurcharge, FxFee fxFee, PriceList priceList, Today today, LateInterestRate lateInterestRate, Holidays holidays,
            VolumeDiscounts volumeDiscounts, RegionGrace regionGrace, RegionTerms regionTerms) {
        this.regionTerms = regionTerms;
        this.regionGrace = regionGrace;
        this.volumeDiscounts = volumeDiscounts;
        this.lateInterestRate = lateInterestRate;
        this.holidays = holidays;
        this.today = today;
        this.priceList = priceList;
        this.cardSurcharge = cardSurcharge;
        this.fxFee = fxFee;
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
        jdbc.execute("DELETE FROM payment_match");
        jdbc.execute("DELETE FROM client_rate");
        jdbc.execute("DELETE FROM price_list_rate");
        jdbc.execute("DELETE FROM holiday");
        jdbc.execute("DELETE FROM invoice_reminder");
        for (String table : List.of("payment", "invoice_milestone", "invoice_line", "invoice", "client")) {
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
            if (values.get("interestThreshold") != null) {
                BigDecimal threshold = money(values.get("interestThreshold"), "settings.interestThreshold");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        INTEREST_THRESHOLD, threshold.toPlainString());
            }
            if (values.get("withholdingThreshold") != null) {
                BigDecimal threshold = money(values.get("withholdingThreshold"), "settings.withholdingThreshold");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        WITHHOLDING_THRESHOLD, threshold.toPlainString());
            }
            if (values.get("approvalLimit") != null) {
                BigDecimal limit = money(values.get("approvalLimit"), "settings.approvalLimit");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        APPROVAL_LIMIT, limit.toPlainString());
            }
            if (values.get("duplicateDays") != null) {
                Integer days = days(values.get("duplicateDays"), "settings.duplicateDays");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        DuplicateWindow.DUPLICATE_DAYS, days.toString());
            }
            if (values.get("tierApprovalLimits") != null) {
                if (!(values.get("tierApprovalLimits") instanceof Map<?, ?> limits)) {
                    throw refuse("settings.tierApprovalLimits must be an object of US dollars by tier");
                }
                for (Map.Entry<?, ?> e : limits.entrySet()) {
                    ClientTier tier = e.getKey() == null ? null : ClientTier.orStandard(e.getKey().toString());
                    if (tier == null || e.getKey().toString().isBlank() || e.getValue() == null) {
                        throw refuse("settings.tierApprovalLimits needs a tier (STANDARD, KEY or NEW) and a limit, not " + e.getKey() + ": " + e.getValue());
                    }
                    BigDecimal limit = money(e.getValue(), "settings.tierApprovalLimits." + tier);
                    jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                            APPROVAL_LIMIT + "_" + tier.name(), limit.toPlainString());
                }
            }
            if (values.get("cardSurchargePct") != null) {
                BigDecimal rate = percent(values.get("cardSurchargePct"), "settings.cardSurchargePct");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        CardSurcharge.CARD_SURCHARGE_PCT, rate.toPlainString());
            }
            if (values.get("cardSurchargeCap") != null) {
                BigDecimal cap = money(values.get("cardSurchargeCap"), "settings.cardSurchargeCap");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        CardSurcharge.CARD_SURCHARGE_CAP, cap.toPlainString());
            }
            if (values.get("fxFeePct") != null) {
                BigDecimal rate = percent(values.get("fxFeePct"), "settings.fxFeePct");
                jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)",
                        FxFee.FX_FEE_PCT, rate.toPlainString());
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
            if (values.get("holidays") != null) {
                if (!(values.get("holidays") instanceof List<?> days)) {
                    throw refuse("settings.holidays must be a list of dates");
                }
                holidays.set(days.stream().map(d -> date(d, "settings.holidays[]")).toList());
            }
            if (values.get("regionRoundDown") != null) {
                if (!(values.get("regionRoundDown") instanceof List<?> regions)) {
                    throw refuse("settings.regionRoundDown must be a list of regions");
                }
                for (Object region : regions) {
                    if (region == null || region.toString().isBlank()) {
                        throw refuse("settings.regionRoundDown needs regions, not " + region);
                    }
                    taxRate.setRoundsDown(region.toString());
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
            if (values.get("regionGraceDays") != null) {
                if (!(values.get("regionGraceDays") instanceof Map<?, ?> graces)) {
                    throw refuse("settings.regionGraceDays must be an object of days by region");
                }
                for (Map.Entry<?, ?> e : graces.entrySet()) {
                    String region = e.getKey().toString();
                    if (region.isBlank() || e.getValue() == null) {
                        throw refuse("settings.regionGraceDays needs a region and days, not " + region + ": " + e.getValue());
                    }
                    regionGrace.set(region, graceDays(e.getValue(), "settings.regionGraceDays." + region));
                }
            }
            if (values.get("regionTermsDays") != null) {
                if (!(values.get("regionTermsDays") instanceof Map<?, ?> terms)) {
                    throw refuse("settings.regionTermsDays must be an object of days by region");
                }
                for (Map.Entry<?, ?> e : terms.entrySet()) {
                    String region = e.getKey().toString();
                    if (region.isBlank() || e.getValue() == null) {
                        throw refuse("settings.regionTermsDays needs a region and days, not " + region + ": " + e.getValue());
                    }
                    regionTerms.set(region, days(e.getValue(), "settings.regionTermsDays." + region));
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
                only(rate, Set.of("unitType", "from", "unitPrice", "tiers"), "settings.rates[]");
                if (rate.get("unitType") == null || rate.get("unitType").toString().isBlank()) {
                    throw refuse("settings.rates[].unitType is required");
                }
                RateTiers tiers = tiers(rate.get("tiers"), "settings.rates[].tiers");
                if ((tiers == null) == (rate.get("unitPrice") == null)) {
                    throw refuse("settings.rates[] needs either unitPrice or tiers");
                }
                priceList.add(rate.get("unitType").toString(),
                        rate.get("from") == null ? null : date(rate.get("from"), "settings.rates[].from"),
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
            ClientTier tier = ClientTier.orStandard(row.get("tier") == null ? null : row.get("tier").toString());
            if (tier == null) {
                throw refuse("unsupported value of clients[].tier: " + row.get("tier"));
            }
            insert("client", row.get("id"), new String[] {"name", "email", "terms_days", "discount_pct", "tax_exempt", "prices_include_tax", "currency", "early_pay_pct", "early_pay_days", "grace_days", "interest_rate", "region", "tax_number", "withholding_pct", "minimum_charge", "rate_discount_pct", "tax_exempt_until", "credit_limit", "on_hold", "tier", "uplift_pct"},
                    text(row, "name"), text(row, "email"), days(row.get("termsDays"), "clients[].termsDays"),
                    percent(row.get("discountPct"), "clients[].discountPct"),
                    flag(row.get("taxExempt"), "clients[].taxExempt"),
                    flag(row.get("pricesIncludeTax"), "clients[].pricesIncludeTax"), currency,
                    percent(row.get("earlyPayPct"), "clients[].earlyPayPct"),
                    days(row.get("earlyPayDays"), "clients[].earlyPayDays"),
                    graceDays(row.get("graceDays"), "clients[].graceDays"),
                    interestRate(row.get("interestRate"), "clients[].interestRate"),
                    row.get("region") == null ? null : row.get("region").toString(),
                    row.get("taxNumber") == null ? null : row.get("taxNumber").toString(),
                    percent(row.get("withholdingPct"), "clients[].withholdingPct"),
                    row.get("minimumCharge") == null ? null : money(row.get("minimumCharge"), "clients[].minimumCharge"),
                    percent(row.get("rateDiscountPct"), "clients[].rateDiscountPct"),
                    row.get("taxExemptUntil") == null ? null : date(row.get("taxExemptUntil"), "clients[].taxExemptUntil"),
                    row.get("creditLimit") == null ? null : money(row.get("creditLimit"), "clients[].creditLimit"),
                    flag(row.get("onHold"), "clients[].onHold"), tier.name(),
                    percent(row.get("upliftPct"), "clients[].upliftPct"));
            Long clientId = id(row.get("id") == null
                    ? jdbc.queryForObject("SELECT MAX(id) FROM client", Long.class) : row.get("id"));
            for (Map<String, Object> rate : rows(row.get("rates"), "clients[].rates")) {
                only(rate, Set.of("unitType", "from", "unitPrice", "tiers", "increment", "minUnits", "taxRate"), "clients[].rates[]");
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
                jdbc.update("INSERT INTO client_rate (client_id, unit_type, effective_from, unit_price, tiers, increment, min_units, tax_rate) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", clientId,
                        rate.get("unitType").toString(),
                        rate.get("from") == null ? null : date(rate.get("from"), "clients[].rates[].from"),
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
            // An adjustment note taking lines off (with negative quantities) is a credit note against the invoice it adjusts,
            // stored with the quantities it credits.
            Object creditOf = row.get("creditOf");
            boolean adjustmentCredit = row.get("adjustmentOf") != null
                    && rows(row.get("lineItems"), "invoices[].lineItems").stream()
                            .anyMatch(l -> l.get("qty") != null && new BigDecimal(l.get("qty").toString()).signum() < 0);
            if (adjustmentCredit && creditOf == null) {
                creditOf = row.get("adjustmentOf");
            }
            // A credit note that has been sent is an issued credit note.
            if ("SENT".equals(status) && creditOf != null) {
                status = "CREDIT_NOTE";
            }
            if (row.get("clientId") == null) {
                throw refuse("invoices[].clientId is required");
            }
            Long invoiceId = insert("invoice", row.get("id"),
                    new String[] {"client_id", "status", "issue_date", "due_date", "discount_pct", "discount_amount", "discount_cap", "discount_reason", "manual_due_date", "credit_of", "interest_waived_on", "written_off_on", "surcharge_pct", "supply_region", "approved_total", "approved_on", "approvals", "recurring", "recurring_from", "recur_until", "disputed_on", "dispute_resolved_on", "dispute_credit", "po_number", "claim_from", "recurring_since", "sent_total", "sent_tax", "rebilled_on", "replaces", "adjustment_of", "promise_amount", "promise_date", "promised_on"},
                    id(row.get("clientId")), status,
                    row.get("issueDate") == null ? null : date(row.get("issueDate"), "invoices[].issueDate"),
                    row.get("dueDate") == null ? null : date(row.get("dueDate"), "invoices[].dueDate"),
                    percent(row.get("discountPct"), "invoices[].discountPct"),
                    money(row.get("discountAmount"), "invoices[].discountAmount"),
                    row.get("discountCap") == null ? null : money(row.get("discountCap"), "invoices[].discountCap"),
                    row.get("discountReason") == null ? null : row.get("discountReason").toString(),
                    flag(row.get("manualDueDate"), "invoices[].manualDueDate"),
                    creditOf == null ? null : id(creditOf),
                    row.get("interestWaivedOn") == null ? null
                            : date(row.get("interestWaivedOn"), "invoices[].interestWaivedOn"),
                    row.get("writtenOffOn") == null ? null
                            : date(row.get("writtenOffOn"), "invoices[].writtenOffOn"),
                    surchargePct(row.get("surchargePct"), "invoices[].surchargePct"),
                    row.get("supplyRegion") == null || row.get("supplyRegion").toString().isBlank() ? null
                            : row.get("supplyRegion").toString(),
                    row.get("approvedTotal") == null ? null : money(row.get("approvedTotal"), "invoices[].approvedTotal"),
                    row.get("approvedOn") == null ? null : date(row.get("approvedOn"), "invoices[].approvedOn"),
                    approvals(row.get("approvals"), row.get("approvedTotal") != null),
                    flag(row.get("recurring"), "invoices[].recurring"),
                    row.get("recurringFrom") == null ? null : id(row.get("recurringFrom")),
                    row.get("recurUntil") == null ? null : date(row.get("recurUntil"), "invoices[].recurUntil"),
                    row.get("disputedOn") == null ? null : date(row.get("disputedOn"), "invoices[].disputedOn"),
                    row.get("disputeResolvedOn") == null ? null
                            : date(row.get("disputeResolvedOn"), "invoices[].disputeResolvedOn"),
                    flag(row.get("disputeCredit"), "invoices[].disputeCredit"),
                    row.get("poNumber") == null ? null : row.get("poNumber").toString(),
                    row.get("claimFrom") == null ? null : id(row.get("claimFrom")),
                    row.get("recurringSince") == null ? null : date(row.get("recurringSince"), "invoices[].recurringSince"),
                    row.get("sentTotal") == null ? null : cents(row.get("sentTotal"), "invoices[].sentTotal"),
                    row.get("sentTax") == null ? null : cents(row.get("sentTax"), "invoices[].sentTax"),
                    row.get("rebilledOn") == null ? null : date(row.get("rebilledOn"), "invoices[].rebilledOn"),
                    row.get("replaces") == null ? null : id(row.get("replaces")),
                    row.get("adjustmentOf") == null ? null : id(row.get("adjustmentOf")),
                    row.get("promiseAmount") == null ? null : money(row.get("promiseAmount"), "invoices[].promiseAmount"),
                    row.get("promiseDate") == null ? null : date(row.get("promiseDate"), "invoices[].promiseDate"),
                    row.get("promisedOn") == null ? null : date(row.get("promisedOn"), "invoices[].promisedOn"));
            for (Map<String, Object> line : rows(row.get("lineItems"), "invoices[].lineItems")) {
                only(line, LINE_FIELDS, "invoices[].lineItems[]");
                Object[] price = linePrice(invoiceId, line);
                LocalDate[] period = {
                        line.get("periodStart") == null ? null : date(line.get("periodStart"), "invoices[].lineItems[].periodStart"),
                        line.get("periodEnd") == null ? null : date(line.get("periodEnd"), "invoices[].lineItems[].periodEnd")};
                if ((period[0] == null) != (period[1] == null)) {
                    throw refuse("invoices[].lineItems[].periodStart and periodEnd must be given together");
                }
                if (period[0] != null && period[1].isBefore(period[0])) {
                    throw refuse("invoices[].lineItems[].periodEnd must not be before periodStart");
                }
                insert("invoice_line", line.get("id"),
                        new String[] {"invoice_id", "description", "qty", "unit_price", "tiers", "tax_exempt", "discount_pct", "tax_rate", "credit_of_line", "unit_type", "billing_increment", "billing_min_units", "overtime", "card_priced", "rate_priced", "period_start", "period_end", "disputed", "percent_complete", "billed_pct", "interest_of"},
                        invoiceId, text(line, "description"),
                        adjustmentCredit ? cents(line.get("qty"), "invoices[].lineItems[].qty").negate()
                                : cents(line.get("qty"), "invoices[].lineItems[].qty"),
                        price[0], price[1],
                        flag(line.get("taxExempt"), "invoices[].lineItems[].taxExempt"),
                        percent(line.get("discountPct"), "invoices[].lineItems[].discountPct"),
                        line.get("taxRate") == null ? null : percent(line.get("taxRate"), "invoices[].lineItems[].taxRate"),
                        line.get("creditOfLine") == null ? null : id(line.get("creditOfLine")),
                        line.get("unitType") == null ? null : line.get("unitType").toString(),
                        lineRate(invoiceId, line, "increment"), lineRate(invoiceId, line, "min_units"),
                        flag(line.get("overtime"), "invoices[].lineItems[].overtime"),
                        price[2], price[3],
                        period[0], period[1],
                        flag(line.get("disputed"), "invoices[].lineItems[].disputed"),
                        percentComplete(line.get("percentComplete"), "invoices[].lineItems[].percentComplete"),
                        line.get("billedPct") == null ? null : percent(line.get("billedPct"), "invoices[].lineItems[].billedPct"),
                        line.get("interestOf") == null ? null : id(line.get("interestOf")));
            }
            if (row.get("reminders") != null) {
                if (!(row.get("reminders") instanceof List<?> sent)) {
                    throw refuse("invoices[].reminders must be a list of dates");
                }
                for (Object on : sent) {
                    jdbc.update("INSERT INTO invoice_reminder (invoice_id, sent_on) VALUES (?, ?)", invoiceId,
                            date(on, "invoices[].reminders[]"));
                }
            }
            BigDecimal pctSum = BigDecimal.ZERO;
            for (Map<String, Object> milestone : rows(row.get("milestones"), "invoices[].milestones")) {
                only(milestone, MILESTONE_FIELDS, "invoices[].milestones[]");
                BigDecimal pct = percent(milestone.get("pct"), "invoices[].milestones[].pct");
                if (milestone.get("pct") == null || pct.signum() <= 0) {
                    throw refuse("invoices[].milestones[].pct must be more than 0 and at most 100, not " + milestone.get("pct"));
                }
                pctSum = pctSum.add(pct);
                insert("invoice_milestone", milestone.get("id"),
                        new String[] {"invoice_id", "description", "pct", "reached_on", "disputed_on"},
                        invoiceId,
                        text(milestone, "description"), pct,
                        milestone.get("reachedOn") == null ? null : date(milestone.get("reachedOn"), "invoices[].milestones[].reachedOn"),
                        milestone.get("disputedOn") == null ? null : date(milestone.get("disputedOn"), "invoices[].milestones[].disputedOn"));
            }
            if (pctSum.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw refuse("invoices[].milestones[].pct must add up to at most 100, not " + pctSum.toPlainString());
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
                    ? cardSurcharge.of(amount, clientCurrency, row.get("date") == null ? today.date()
                            : date(row.get("date"), "payments[].date")) : BigDecimal.ZERO;
            // Part of a payment in another currency is the workspace's conversion fee, as when one is recorded.
            BigDecimal fee = foreign ? fxFee.of(amount, clientCurrency.scale()) : BigDecimal.ZERO;
            Long paymentId = insert("payment", row.get("id"), new String[] {"client_id", "amount", "payment_date", "reference", "reversed", "refunded", "credit_note", "paid_currency", "paid_amount", "method", "surcharge", "fx_fee", "remittance", "held_for", "deposit"},
                    id(row.get("clientId")), amount,
                    date(row.get("date"), "payments[].date"),
                    row.get("reference") == null ? null : row.get("reference").toString(),
                    flag(row.get("reversed"), "payments[].reversed"),
                    money(row.get("refunded"), "payments[].refunded"),
                    row.get("creditNote") == null ? null : id(row.get("creditNote")),
                    foreign ? paidIn.name() : null, foreign ? paidAmount : null, method, surcharge, fee,
                    row.get("remittance") == null ? null : row.get("remittance").toString(),
                    row.get("heldFor") == null ? null : id(row.get("heldFor")),
                    flag(row.get("deposit"), "payments[].deposit"));
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
        // An issued credit note keeps its grand total, which comes off what its original owes, in the
        // order they were issued; any rest is the client's credit.
        BigDecimal rate = taxRate.percent();
        for (Long noteId : jdbc.queryForList("SELECT id FROM invoice WHERE status = 'CREDIT_NOTE' ORDER BY id", Long.class)) {
            Invoice note = invoices.findWithLinesById(noteId).orElseThrow();
            BigDecimal total = note.total(rate);
            BigDecimal applied = BigDecimal.ZERO;
            Invoice original = invoices.findWithLinesById(note.getCreditOf()).orElse(null);
            if (original != null) {
                entityManager.refresh(original);
                BigDecimal paid = payments.paidTo(List.of(original.getId())).stream()
                        .map(r -> (BigDecimal) r[1]).findFirst().orElse(BigDecimal.ZERO);
                applied = original.creditTaken(total, paid, rate);
            }
            jdbc.update("UPDATE invoice SET credit_total = ?, credit_applied = ? WHERE id = ?",
                    total, applied, noteId);
        }
        // Keep generated ids clear of the seeded ones.
        for (String table : List.of("client", "invoice", "invoice_line", "invoice_milestone", "payment")) {
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
        cardSurcharge.cap().ifPresent(cap -> settings.put("cardSurchargeCap", cap));
        List<String> fxFeePct = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, FxFee.FX_FEE_PCT);
        if (!fxFeePct.isEmpty()) {
            settings.put("fxFeePct", new BigDecimal(fxFeePct.get(0)));
        }
        List<String> minimumCharge = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, MINIMUM_CHARGE);
        if (!minimumCharge.isEmpty()) {
            settings.put("minimumCharge", new BigDecimal(minimumCharge.get(0)));
        }
        List<String> approvalLimit = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, APPROVAL_LIMIT);
        if (!approvalLimit.isEmpty()) {
            settings.put("approvalLimit", new BigDecimal(approvalLimit.get(0)));
        }
        jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?", String.class,
                DuplicateWindow.DUPLICATE_DAYS).forEach(v -> settings.put("duplicateDays", Integer.valueOf(v)));
        Map<String, BigDecimal> tierApprovalLimits = new LinkedHashMap<>();
        for (ClientTier tier : ClientTier.values()) {
            jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?", String.class,
                    APPROVAL_LIMIT + "_" + tier.name()).forEach(v -> tierApprovalLimits.put(tier.name(), new BigDecimal(v)));
        }
        if (!tierApprovalLimits.isEmpty()) {
            settings.put("tierApprovalLimits", tierApprovalLimits);
        }
        List<String> interestThreshold = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, INTEREST_THRESHOLD);
        if (!interestThreshold.isEmpty()) {
            settings.put("interestThreshold", new BigDecimal(interestThreshold.get(0)));
        }
        List<String> withholdingThreshold = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE name = ?",
                String.class, WITHHOLDING_THRESHOLD);
        if (!withholdingThreshold.isEmpty()) {
            settings.put("withholdingThreshold", new BigDecimal(withholdingThreshold.get(0)));
        }
        Map<String, BigDecimal> regionMaxInterest = this.lateInterestRate.regionMaximums();
        if (!regionMaxInterest.isEmpty()) {
            settings.put("regionMaxInterest", regionMaxInterest);
        }
        Map<String, Integer> regionGraceDays = this.regionGrace.all();
        if (!regionGraceDays.isEmpty()) {
            settings.put("regionGraceDays", regionGraceDays);
        }
        Map<String, Integer> regionTermsDays = this.regionTerms.all();
        if (!regionTermsDays.isEmpty()) {
            settings.put("regionTermsDays", regionTermsDays);
        }
        Map<String, BigDecimal> regionTaxRates = this.taxRate.regions();
        if (!regionTaxRates.isEmpty()) {
            settings.put("regionTaxRates", regionTaxRates);
        }
        holidays.calendar().ifPresent(days -> settings.put("holidays", days.stream().map(LocalDate::toString).toList()));
        List<String> regionRoundDown = this.taxRate.roundDownRegions();
        if (!regionRoundDown.isEmpty()) {
            settings.put("regionRoundDown", regionRoundDown);
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
                .map(e -> rate(e.unitType(), e.from(), e.unitPrice(), e.tiers())).toList();
        if (!priceListRates.isEmpty()) {
            settings.put("rates", priceListRates);
        }
        if (!settings.isEmpty()) {
            out.put("settings", settings);
        }
        out.put("clients", jdbc.queryForList("SELECT id, name, email, terms_days, discount_pct, tax_exempt, prices_include_tax, currency, early_pay_pct, early_pay_days, grace_days, interest_rate, region, tax_number, withholding_pct, minimum_charge, rate_discount_pct, tax_exempt_until, credit_limit, on_hold, tier, uplift_pct FROM client ORDER BY id").stream()
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
                    row.put("withholdingPct", r.get("WITHHOLDING_PCT"));
                    row.put("rateDiscountPct", r.get("RATE_DISCOUNT_PCT"));
                    row.put("onHold", r.get("ON_HOLD"));
                    row.put("tier", r.get("TIER"));
                    row.put("upliftPct", r.get("UPLIFT_PCT"));
                    if (r.get("TAX_EXEMPT_UNTIL") != null) {
                        row.put("taxExemptUntil", r.get("TAX_EXEMPT_UNTIL").toString());
                    }
                    if (r.get("CREDIT_LIMIT") != null) {
                        row.put("creditLimit", r.get("CREDIT_LIMIT"));
                    }
                    if (r.get("MINIMUM_CHARGE") != null) {
                        row.put("minimumCharge", r.get("MINIMUM_CHARGE"));
                    }
                    if (r.get("GRACE_DAYS") != null) {
                        row.put("graceDays", r.get("GRACE_DAYS"));
                    }
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
                            "SELECT unit_type, effective_from, unit_price, tiers, increment, min_units, tax_rate FROM client_rate WHERE client_id = ? ORDER BY unit_type, effective_from NULLS FIRST",
                            r.get("ID")).stream().map(c -> {
                                Map<String, Object> entry = rate((String) c.get("UNIT_TYPE"),
                                        c.get("EFFECTIVE_FROM") == null ? null : ((java.sql.Date) c.get("EFFECTIVE_FROM")).toLocalDate(),
                                        (BigDecimal) c.get("UNIT_PRICE"),
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
                "SELECT id, client_id, status, issue_date, due_date, discount_pct, discount_amount, discount_cap, discount_reason, manual_due_date, credit_of, interest_waived_on, written_off_on, surcharge_pct, supply_region, approved_total, approved_on, approvals, recurring, recurring_from, recur_until, disputed_on, dispute_resolved_on, dispute_credit, po_number, claim_from, recurring_since, sent_total, sent_tax, rebilled_on, replaces, adjustment_of, promise_amount, promise_date, promised_on FROM invoice ORDER BY id").stream()
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
                    if (r.get("INTEREST_WAIVED_ON") != null) {
                        row.put("interestWaivedOn", r.get("INTEREST_WAIVED_ON").toString());
                    }
                    if (r.get("WRITTEN_OFF_ON") != null) {
                        row.put("writtenOffOn", r.get("WRITTEN_OFF_ON").toString());
                    }
                    if (r.get("APPROVED_TOTAL") != null) {
                        row.put("approvedTotal", r.get("APPROVED_TOTAL"));
                    }
                    if (r.get("APPROVED_ON") != null) {
                        row.put("approvedOn", r.get("APPROVED_ON").toString());
                    }
                    row.put("approvals", r.get("APPROVALS"));
                    row.put("surchargePct", r.get("SURCHARGE_PCT"));
                    row.put("recurring", r.get("RECURRING"));
                    if (r.get("DISPUTED_ON") != null) {
                        row.put("disputedOn", r.get("DISPUTED_ON").toString());
                    }
                    if (r.get("DISPUTE_RESOLVED_ON") != null) {
                        row.put("disputeResolvedOn", r.get("DISPUTE_RESOLVED_ON").toString());
                    }
                    row.put("disputeCredit", r.get("DISPUTE_CREDIT"));
                    if (r.get("RECUR_UNTIL") != null) {
                        row.put("recurUntil", r.get("RECUR_UNTIL").toString());
                    }
                    if (r.get("RECURRING_SINCE") != null) {
                        row.put("recurringSince", r.get("RECURRING_SINCE").toString());
                    }
                    if (r.get("SENT_TOTAL") != null) {
                        row.put("sentTotal", r.get("SENT_TOTAL"));
                    }
                    if (r.get("SENT_TAX") != null) {
                        row.put("sentTax", r.get("SENT_TAX"));
                    }
                    if (r.get("RECURRING_FROM") != null) {
                        row.put("recurringFrom", r.get("RECURRING_FROM"));
                    }
                    if (r.get("CLAIM_FROM") != null) {
                        row.put("claimFrom", r.get("CLAIM_FROM"));
                    }
                    if (r.get("REBILLED_ON") != null) {
                        row.put("rebilledOn", r.get("REBILLED_ON").toString());
                    }
                    if (r.get("REPLACES") != null) {
                        row.put("replaces", r.get("REPLACES"));
                    }
                    if (r.get("ADJUSTMENT_OF") != null) {
                        row.put("adjustmentOf", r.get("ADJUSTMENT_OF"));
                    }
                    if (r.get("PROMISE_AMOUNT") != null) {
                        row.put("promiseAmount", r.get("PROMISE_AMOUNT"));
                    }
                    if (r.get("PROMISE_DATE") != null) {
                        row.put("promiseDate", r.get("PROMISE_DATE").toString());
                    }
                    if (r.get("PROMISED_ON") != null) {
                        row.put("promisedOn", r.get("PROMISED_ON").toString());
                    }
                    if (r.get("CREDIT_OF") != null) {
                        row.put("creditOf", r.get("CREDIT_OF"));
                    }
                    if (r.get("DISCOUNT_REASON") != null) {
                        row.put("discountReason", r.get("DISCOUNT_REASON"));
                    }
                    if (r.get("PO_NUMBER") != null) {
                        row.put("poNumber", r.get("PO_NUMBER"));
                    }
                    if (r.get("SUPPLY_REGION") != null) {
                        row.put("supplyRegion", r.get("SUPPLY_REGION"));
                    }
                    row.put("reminders", jdbc.queryForList(
                            "SELECT sent_on FROM invoice_reminder WHERE invoice_id = ? ORDER BY sent_on", LocalDate.class,
                            r.get("ID")).stream().map(LocalDate::toString).toList());
                    row.put("milestones", jdbc.queryForList(
                            "SELECT id, description, pct, reached_on, disputed_on FROM invoice_milestone WHERE invoice_id = ? ORDER BY id",
                            r.get("ID")).stream().map(m -> {
                                Map<String, Object> milestone = new LinkedHashMap<>();
                                milestone.put("id", m.get("ID"));
                                milestone.put("description", m.get("DESCRIPTION"));
                                milestone.put("pct", m.get("PCT"));
                                if (m.get("REACHED_ON") != null) {
                                    milestone.put("reachedOn", m.get("REACHED_ON").toString());
                                }
                                if (m.get("DISPUTED_ON") != null) {
                                    milestone.put("disputedOn", m.get("DISPUTED_ON").toString());
                                }
                                return milestone;
                            }).toList());
                    row.put("lineItems", jdbc.queryForList(
                            "SELECT id, description, qty, unit_type, unit_price, tax_exempt, discount_pct, tax_rate, credit_of_line, overtime, period_start, period_end, disputed, percent_complete, billed_pct, interest_of FROM invoice_line WHERE invoice_id = ? ORDER BY id",
                            r.get("ID")).stream().map(l -> {
                                Map<String, Object> line = new LinkedHashMap<>();
                                line.put("id", l.get("ID"));
                                line.put("description", l.get("DESCRIPTION"));
                                // A credit note adjusting an invoice takes its lines off: their quantities read back negative.
                                line.put("qty", r.get("ADJUSTMENT_OF") != null && r.get("CREDIT_OF") != null
                                        ? ((BigDecimal) l.get("QTY")).negate() : l.get("QTY"));
                                if (l.get("UNIT_TYPE") != null) {
                                    line.put("unitType", l.get("UNIT_TYPE"));
                                }
                                line.put("unitPrice", l.get("UNIT_PRICE"));
                                line.put("taxExempt", l.get("TAX_EXEMPT"));
                                line.put("overtime", l.get("OVERTIME"));
                                line.put("disputed", l.get("DISPUTED"));
                                line.put("discountPct", l.get("DISCOUNT_PCT"));
                                if (l.get("TAX_RATE") != null) {
                                    line.put("taxRate", l.get("TAX_RATE"));
                                }
                                if (l.get("CREDIT_OF_LINE") != null) {
                                    line.put("creditOfLine", l.get("CREDIT_OF_LINE"));
                                }
                                if (l.get("PERIOD_START") != null) {
                                    line.put("periodStart", l.get("PERIOD_START").toString());
                                    line.put("periodEnd", l.get("PERIOD_END").toString());
                                }
                                if (l.get("PERCENT_COMPLETE") != null) {
                                    line.put("percentComplete", l.get("PERCENT_COMPLETE"));
                                }
                                if (l.get("BILLED_PCT") != null) {
                                    line.put("billedPct", l.get("BILLED_PCT"));
                                }
                                if (l.get("INTEREST_OF") != null) {
                                    line.put("interestOf", l.get("INTEREST_OF"));
                                }
                                return line;
                            }).toList());
                    return row;
                }).toList());
        out.put("payments", jdbc.queryForList("SELECT p.id, p.client_id, p.amount, p.payment_date, p.reference, p.reversed, p.refunded, p.credit_note, p.held_for, p.method, p.remittance, p.deposit, "
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
                    row.put("deposit", r.get("DEPOSIT"));
                    if (r.get("REMITTANCE") != null) {
                        row.put("remittance", r.get("REMITTANCE"));
                    }
                    if (r.get("CREDIT_NOTE") != null) {
                        row.put("creditNote", r.get("CREDIT_NOTE"));
                    }
                    if (r.get("HELD_FOR") != null) {
                        row.put("heldFor", r.get("HELD_FOR"));
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
     * given, or else its invoice's client's rate for the line's unit type in force on the day the invoice is
     * priced as of (see {@link #ratesDate}; its issue date when nothing is in force then) — a single price, or tiers whose first band's price is kept as the unit price — or else the price
     * list's then; refused when neither is there — and whether the line is priced from the rates, which a
     * draft's line follows.
     */
    private Object[] linePrice(Long invoiceId, Map<String, Object> line) {
        if (line.get("unitPrice") != null || line.get("unitType") == null) {
            return new Object[] {cents(line.get("unitPrice"), "invoices[].lineItems[].unitPrice"), null, false, false};
        }
        Map<String, Object> invoice = jdbc.queryForMap("SELECT i.status, i.issue_date, c.currency, c.rate_discount_pct FROM invoice i JOIN client c ON c.id = i.client_id WHERE i.id = ?", invoiceId);
        LocalDate issued = invoiceDate(invoice.get("ISSUE_DATE"));
        LocalDate on = ratesDate(invoiceId);
        if (!on.equals(issued)) {
            try {
                return linePrice(invoiceId, line, invoice, on);
            } catch (ResponseStatusException noRateToday) {
                // nothing in force today: priced as of its issue date instead
            }
        }
        return linePrice(invoiceId, line, invoice, issued);
    }

    /** A seeded line's price as {@link #linePrice(Long, Map)}, from the rates in force on the date. */
    private Object[] linePrice(Long invoiceId, Map<String, Object> line, Map<String, Object> invoice, LocalDate on) {
        Currency currency = Currency.orUsd((String) invoice.get("CURRENCY"));
        BigDecimal discountPct = (BigDecimal) invoice.get("RATE_DISCOUNT_PCT");
        // A draft's line priced from the rates follows them; a sent invoice's has its price fixed.
        boolean draft = "DRAFT".equals(invoice.get("STATUS"));
        List<Map<String, Object>> rate = jdbc.queryForList("SELECT r.unit_price, r.tiers FROM client_rate r JOIN invoice i ON i.client_id = r.client_id "
                + "WHERE i.id = ? AND LOWER(r.unit_type) = LOWER(?) AND (r.effective_from IS NULL OR r.effective_from <= ?) "
                + "ORDER BY r.effective_from DESC NULLS LAST LIMIT 1", invoiceId,
                line.get("unitType").toString().trim(), on);
        if (rate.isEmpty()) {
            PriceList.Converted listed = priceList.priceFor(line.get("unitType").toString(), currency, on);
            if (listed == null) {
                throw refuse("invoices[].lineItems[].unitPrice is required: neither the client's rate card nor the price list has unitType "
                        + line.get("unitType") + " in force on the issue date");
            }
            return new Object[] {RateDiscount.price(listed.unitPrice(), discountPct, currency),
                    RateDiscount.tiers(listed.tiers(), discountPct, currency), draft, true};
        }
        RateTiers tiers = RateTiers.parse((String) rate.get(0).get("TIERS"));
        if (tiers != null) {
            return new Object[] {RateDiscount.price(tiers.firstPrice(), discountPct, currency),
                    RateDiscount.tiers(tiers.format(), discountPct, currency), draft, true};
        }
        return new Object[] {RateDiscount.price((BigDecimal) rate.get(0).get("UNIT_PRICE"), discountPct, currency), null, draft, true};
    }

    /**
     * The day a seeded invoice's lines are priced as of: today for a draft (not a credit note), which will be
     * dated today when sent; otherwise its issue date, or today when it has none.
     */
    private LocalDate ratesDate(Long invoiceId) {
        Map<String, Object> invoice = jdbc.queryForMap("SELECT status, credit_of, issue_date FROM invoice WHERE id = ?", invoiceId);
        return "DRAFT".equals(invoice.get("STATUS")) && invoice.get("CREDIT_OF") == null ? today.date()
                : invoiceDate(invoice.get("ISSUE_DATE"));
    }

    /** The day an invoice's rates are taken on: its issue date, or today when it has none. */
    private LocalDate invoiceDate(Object issueDate) {
        return issueDate == null ? today.date() : ((java.sql.Date) issueDate).toLocalDate();
    }

    /**
     * A seeded line's billing increment or minimum units: its invoice's client's given column for the line's unit type
     * in the entry in force on the day the invoice is priced as of (see {@link #ratesDate}), or else on its issue date;
     * null for none.
     */
    private BigDecimal lineRate(Long invoiceId, Map<String, Object> line, String column) {
        if (line.get("unitType") == null) {
            return null;
        }
        LocalDate issued = invoiceDate(jdbc.queryForMap("SELECT issue_date FROM invoice WHERE id = ?", invoiceId).get("ISSUE_DATE"));
        LocalDate on = ratesDate(invoiceId);
        if (!on.equals(issued) && jdbc.queryForObject("SELECT COUNT(*) FROM client_rate r JOIN invoice i ON i.client_id = r.client_id "
                + "WHERE i.id = ? AND LOWER(r.unit_type) = LOWER(?) AND (r.effective_from IS NULL OR r.effective_from <= ?)",
                Integer.class, invoiceId, line.get("unitType").toString().trim(), on) == 0) {
            on = issued;
        }
        List<BigDecimal> found = jdbc.queryForList("SELECT r." + column + " FROM client_rate r JOIN invoice i ON i.client_id = r.client_id "
                + "WHERE i.id = ? AND LOWER(r.unit_type) = LOWER(?) AND (r.effective_from IS NULL OR r.effective_from <= ?) "
                + "ORDER BY r.effective_from DESC NULLS LAST LIMIT 1", BigDecimal.class, invoiceId,
                line.get("unitType").toString().trim(), on);
        return found.isEmpty() ? null : found.get(0);
    }

    /** A rate card or price list entry as read back: its unit type, the date it takes effect from if any, and a single unitPrice or tiers. */
    private static Map<String, Object> rate(String unitType, LocalDate from, BigDecimal unitPrice, RateTiers tiers) {
        Map<String, Object> rate = new LinkedHashMap<>();
        rate.put("unitType", unitType);
        if (from != null) {
            rate.put("from", from.toString());
        }
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

    /** A grace period of 0 to 30 whole days; null (the region's, else the standard week) when left out. */
    private static Integer graceDays(Object value, String field) {
        Integer days = days(value, field);
        if (days == null) {
            return null;
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

    /**
     * The approvals an invoice has (0, 1 or 2); left out, an approved invoice has one and any other none.
     */
    private static int approvals(Object value, boolean approved) {
        if (value == null) {
            return approved ? 1 : 0;
        }
        try {
            int count = new BigDecimal(value.toString()).intValueExact();
            if (count < 0 || count > 2) {
                throw refuse("invoices[].approvals must be from 0 to 2, not " + value);
            }
            return count;
        } catch (NumberFormatException | ArithmeticException e) {
            throw refuse("invoices[].approvals must be a whole number, not " + value);
        }
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

    /** An optional percent complete, more than 0 and at most 100 with at most two decimals; null (the whole line) when left out. */
    private static BigDecimal percentComplete(Object value, String field) {
        if (value == null) {
            return null;
        }
        BigDecimal number = percent(value, field);
        if (number.signum() <= 0) {
            throw refuse(field + " must be more than 0 and at most 100, not " + value);
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
