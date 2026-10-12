package net.officefloor.hq.app.invoices;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.Formula;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.clients.Currency;
import net.officefloor.hq.app.clients.TaxNumber;

/** An invoice raised for a client, made of lines. */
@Entity
@Table(name = "invoice")
public class Invoice {

    /** A due date set by hand can be at most this many days after the issue date. */
    public static final int MAX_DUE_DAYS = 120;

    /**
     * Payments that leave less than this many US dollars' worth still due, and no more than
     * {@link #WRITE_OFF_SHARE} of the grand total, have the rest written off.
     */
    public static final BigDecimal WRITE_OFF_LIMIT = new BigDecimal("0.05");

    /** A plan's monthly instalment must be worth at least this many US dollars. */
    public static final BigDecimal MIN_INSTALMENT_USD = new BigDecimal("25.00");

    /** The most of the grand total that can be written off as a small balance after a payment. */
    public static final BigDecimal WRITE_OFF_SHARE = new BigDecimal("0.01");

    /** A part payment must be at least this share of the grand total, unless it settles the invoice. */
    public static final BigDecimal MINIMUM_PART_SHARE = new BigDecimal("0.10");

    /** The fewest and most monthly instalments a plan can have. */
    public static final int MIN_INSTALMENTS = 2;
    public static final int MAX_INSTALMENTS = 24;

    /** The highest annual interest rate a plan can charge, as a percentage. */
    public static final BigDecimal MAX_INSTALMENT_RATE = new BigDecimal("30");

    /** The most a draft's surcharge can be, a percentage of its amount after its discounts. */
    public static final BigDecimal MAX_SURCHARGE_PCT = new BigDecimal("25");

    /** The most a plan's down payment can be, a percentage of the grand total. */
    public static final BigDecimal MAX_PLAN_DOWN_PCT = new BigDecimal("50");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id")
    private Long clientId;

    @Enumerated(EnumType.STRING)
    private InvoiceStatus status;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "manual_due_date")
    private boolean manualDueDate;

    /**
     * The region where the work is done (the place of supply): the invoice is taxed at its rate, and
     * reverse charged as for it, instead of the client's region; null for the client's region.
     */
    @Column(name = "supply_region")
    private String supplyRegion;

    /** The client's purchase order number for the invoice; null when none was given. */
    @Column(name = "po_number")
    private String poNumber;

    @Column(name = "discount_pct")
    private BigDecimal discountPct = BigDecimal.ZERO;

    @Column(name = "discount_amount")
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "discount_cap")
    private BigDecimal discountCap;

    @Column(name = "discount_reason")
    private String discountReason;

    /** The rush surcharge, a percentage of the amount after the discounts, 0 to {@link #MAX_SURCHARGE_PCT}. */
    @Column(name = "surcharge_pct")
    private BigDecimal surchargePct = BigDecimal.ZERO;

    /** The day the late interest still unpaid was last waived; null when never. */
    @Column(name = "interest_waived_on")
    private LocalDate interestWaivedOn;

    /** The day the invoice was written off as a bad debt; null when it was not. */
    @Column(name = "written_off_on")
    private LocalDate writtenOffOn;

    /** The day the client disputed the invoice; null when it has never been disputed. */
    @Column(name = "disputed_on")
    private LocalDate disputedOn;

    /** The day the dispute was resolved; null while it is open or when there has been none. */
    @Column(name = "dispute_resolved_on")
    private LocalDate disputeResolvedOn;

    /**
     * Whether the dispute was resolved with a credit note for what was in dispute: the interest it held back
     * on the disputed amount while it was open is then never charged.
     */
    @Column(name = "dispute_credit")
    private boolean disputeCredit;

    /** How many monthly instalments the invoice is spread over; null when it has no plan. */
    @Column(name = "instalments")
    private Integer instalments;

    /** The annual interest rate of the plan, a percentage from 0 to {@link #MAX_INSTALMENT_RATE}. */
    @Column(name = "instalment_rate")
    private BigDecimal instalmentRate = BigDecimal.ZERO;

    /**
     * The annual rate the plan is worked out at: its own rate, but no more than the legal maximum of
     * the client's region.
     */
    public BigDecimal planRate() {
        return regionMaxInterest == null ? instalmentRate : instalmentRate.min(new BigDecimal(regionMaxInterest.trim()));
    }

    /** The plan's down payment, a percentage of the grand total from 0 to {@link #MAX_PLAN_DOWN_PCT}. */
    @Column(name = "plan_down_pct")
    private BigDecimal planDownPct = BigDecimal.ZERO;

    /**
     * The plan fee charged when the plan was set up, in the invoice's currency: added to what is spread
     * over the instalments, after any down payment; 0 when there is none.
     */
    @Column(name = "plan_fee")
    private BigDecimal planFee = BigDecimal.ZERO;

    /**
     * The grand total the invoice was approved at, when it was over the workspace's approval limit;
     * null while it has not been approved.
     */
    @Column(name = "approved_total")
    private BigDecimal approvedTotal;

    /** The day the invoice was approved; null while it has not been, or for an approval given before the day was kept. */
    @Column(name = "approved_on")
    private LocalDate approvedOn;

    /** Whether the invoice repeats every period: once sent, it can generate the next period's draft (see {@link #nextPeriod}). */
    @Column(name = "recurring")
    private boolean recurring;

    /** The id of the recurring invoice this draft was generated from as its next period; null when it was not. */
    @Column(name = "recurring_from")
    private Long recurringFrom;

    /** The last day the recurring invoice bills for: a generated period stops on it; null when it has no end. */
    @Column(name = "recur_until")
    private LocalDate recurUntil;

    /**
     * The number of the instalment the client skipped: it pays only its interest and the instalments
     * after it are worked out again (see {@link #schedule}); null when none was.
     */
    @Column(name = "skipped_instalment")
    private Integer skippedInstalment;

    /** Whether the invoice's client is tax exempt, so no line is taxed; read from the client. */
    @Formula("(SELECT c.tax_exempt FROM client c WHERE c.id = client_id)")
    private boolean clientTaxExempt;

    /** The last issue date the client's tax exemption covers; null when it does not end. Read from the client. */
    @Formula("(SELECT c.tax_exempt_until FROM client c WHERE c.id = client_id)")
    private LocalDate clientTaxExemptUntil;

    /**
     * Whether the invoice's client is quoted prices that include tax, so the line amounts, discount and
     * grand total include it and the tax is taken out of them; read from the client.
     */
    @Formula("(SELECT c.prices_include_tax FROM client c WHERE c.id = client_id)")
    private boolean clientPricesIncludeTax;

    /**
     * The tax percentage of the client's region, from the workspace's regional rates, charged instead
     * of the workspace rate; read from them, null when the client has no region or it has no rate.
     */
    @Formula("(SELECT s.setting_value FROM app_setting s, client c WHERE c.id = client_id AND s.name = 'region_tax_rate_' || COALESCE(supply_region, c.region))")
    private String regionTaxRate;

    /**
     * The dated changes of the client's region's rate, as "from date=rate" joined by commas in date
     * order; null when there are none. Read from the workspace's tax rate changes.
     */
    @Formula("(SELECT LISTAGG(CAST(t.from_date AS VARCHAR) || '=' || CAST(t.rate AS VARCHAR), ',') WITHIN GROUP (ORDER BY t.from_date, t.id) FROM tax_rate_change t, client c WHERE c.id = client_id AND t.region = COALESCE(supply_region, c.region))")
    private String regionRateChanges;

    /**
     * Whether the region the invoice is taxed in (the place of supply, else the client's) requires its tax
     * to be rounded down to the cent instead of half up; read from the workspace's round-down regions.
     */
    @Formula("(SELECT COUNT(*) > 0 FROM app_setting s, client c WHERE c.id = client_id AND s.name = 'region_round_down_' || COALESCE(supply_region, c.region))")
    private boolean regionRoundsDown;

    /**
     * The tax rates of the client's rate card entries, as "unit type", the date the entry takes effect from
     * (empty for always) and rate (empty for none) separated by CHAR(31), entries separated by CHAR(30); null
     * when the card is empty. Read from the client's rate card.
     */
    @Formula("(SELECT LISTAGG(r.unit_type || CHAR(31) || COALESCE(CAST(r.effective_from AS VARCHAR), '') || CHAR(31) || COALESCE(CAST(r.tax_rate AS VARCHAR), ''), CHAR(30)) WITHIN GROUP (ORDER BY r.unit_type) FROM client_rate r WHERE r.client_id = client_id)")
    private String cardTaxRates;

    /**
     * The tiered lines on the client's other sent invoices (not credit notes), as the invoice id, its issue
     * date, the line's unit type and its billed units separated by CHAR(31), lines separated by CHAR(30);
     * null when there are none. Their units count first through the bands of an invoice issued later in
     * the same month (see {@link #tieredUnitsBefore}).
     */
    @Formula("(SELECT LISTAGG(CAST(o.id AS VARCHAR) || CHAR(31) || CAST(o.issue_date AS VARCHAR) || CHAR(31) || l.unit_type || CHAR(31)"
            + " || CAST(GREATEST(CASE WHEN l.billing_increment > 0 THEN CEILING(l.qty / l.billing_increment) * l.billing_increment ELSE l.qty END,"
            + " COALESCE(l.billing_min_units, 0)) AS VARCHAR), CHAR(30)) WITHIN GROUP (ORDER BY o.id, l.id)"
            + " FROM invoice o, invoice_line l WHERE o.client_id = client_id AND o.id <> id AND o.status = 'SENT'"
            + " AND o.credit_of IS NULL AND o.issue_date IS NOT NULL AND l.invoice_id = o.id AND l.tiers IS NOT NULL AND l.unit_type IS NOT NULL)")
    private String clientTieredUnits;

    /** The issue date of the invoice this credit note credits; null for an ordinary invoice. */
    @Formula("(SELECT o.issue_date FROM invoice o WHERE o.id = credit_of)")
    private LocalDate creditedIssueDate;

    /** The surcharge percentage of the invoice this credit note credits; null for an ordinary invoice. */
    @Formula("(SELECT o.surcharge_pct FROM invoice o WHERE o.id = credit_of)")
    private BigDecimal creditedSurchargePct;

    /**
     * The tax number of the invoice's client when the client is in a region other than the workspace's
     * home region and the number's two letters are that region's code; read from them, null when it has
     * no number, no region, a number from another region, is in the home region or there is no home
     * region. With a valid number the invoice is reverse charged (see {@link #isReverseCharge}).
     */
    @Formula("(SELECT c.tax_number FROM client c WHERE c.id = client_id AND COALESCE(supply_region, c.region) IS NOT NULL"
            + " AND UPPER(SUBSTRING(c.tax_number, 1, 2)) = UPPER(COALESCE(supply_region, c.region))"
            + " AND COALESCE(supply_region, c.region) <> (SELECT s.setting_value FROM app_setting s WHERE s.name = 'home_region'))")
    private String outOfRegionTaxNumber;

    /** The id of the invoice this credit note credits; null for an ordinary invoice. */
    @Column(name = "credit_of")
    private Long creditOf;

    /** The code of the currency the invoice's client is billed in; read from the client. */
    @Formula("(SELECT c.currency FROM client c WHERE c.id = client_id)")
    private String clientCurrency;

    /**
     * The US dollars one unit of the client's currency is worth, from the workspace rates; read from
     * them, null when no rate is set (taken at par).
     */
    @Formula("(SELECT s.setting_value FROM app_setting s, client c WHERE c.id = client_id AND s.name = 'usd_per_' || c.currency)")
    private String usdPer;

    /**
     * The dated rates of the client's currency, as "from date=US dollars per unit" joined by commas in
     * date order; null when there are none. Read from the workspace's dated rates.
     */
    @Formula("(SELECT LISTAGG(CAST(r.from_date AS VARCHAR) || '=' || CAST(r.usd_per AS VARCHAR), ',') WITHIN GROUP (ORDER BY r.from_date, r.id) FROM fx_rate r, client c WHERE c.id = client_id AND r.currency = c.currency)")
    private String datedRates;

    /** The latest day a payment (not reversed) was applied to the invoice; null when none was. */
    /**
     * The workspace's minimum charge in US dollars, as stored (the {@code minimum_charge} setting); null
     * when none is set.
     */
    @Formula("(SELECT s.setting_value FROM app_setting s WHERE s.name = 'minimum_charge')")
    private String minimumChargeUsd;

    /**
     * The client's own minimum charge in its currency, charged instead of the workspace's; null for the
     * workspace's, 0 for none. Read from the client.
     */
    @Formula("(SELECT c.minimum_charge FROM client c WHERE c.id = client_id)")
    private BigDecimal clientMinimumCharge;

    /**
     * The workspace's volume discounts, as "threshold in US dollars=percentage" joined by commas;
     * null when there are none. Read from them.
     */
    @Formula("(SELECT LISTAGG(CAST(v.over_usd AS VARCHAR) || '=' || CAST(v.pct AS VARCHAR), ',') WITHIN GROUP (ORDER BY v.over_usd, v.id) FROM volume_discount v)")
    private String volumeDiscounts;

    @Formula("(SELECT MAX(COALESCE(a.applied_date, p.payment_date)) FROM payment_allocation a, payment p WHERE a.invoice_id = id AND p.id = a.payment_id AND p.reversed = FALSE)")
    private LocalDate lastPaidOn;

    /** The client's early-payment discount, a percentage of the amount before tax; read from the client. */
    @Formula("(SELECT c.early_pay_pct FROM client c WHERE c.id = client_id)")
    private BigDecimal earlyPayPct;

    /** How many days after the issue date the invoice counts as paid off early; read from the client. */
    @Formula("(SELECT c.early_pay_days FROM client c WHERE c.id = client_id)")
    private Integer earlyPayDays;

    /** The percentage of the amount before tax the client withholds for its tax office; read from the client. */
    @Formula("(SELECT c.withholding_pct FROM client c WHERE c.id = client_id)")
    private BigDecimal withholdingPct;

    /** The percentage of the amount before tax the client withholds for its tax office; 0 when none. */
    public BigDecimal getWithholdingPct() {
        return withholdingPct == null ? BigDecimal.ZERO : withholdingPct;
    }

    /**
     * The client's grace period in days: their own, else their region's; read from the client and the
     * workspace's regional grace periods, null when neither is set.
     */
    @Formula("(SELECT COALESCE(c.grace_days, (SELECT CAST(s.setting_value AS INT) FROM app_setting s WHERE s.name = 'region_grace_days_' || c.region)) FROM client c WHERE c.id = client_id)")
    private Integer clientGraceDays;

    /**
     * The client's payment terms in days: their own, else their region's; read from the client and the
     * workspace's regional terms, null when neither is set.
     */
    @Formula("(SELECT COALESCE(c.terms_days, (SELECT CAST(s.setting_value AS INT) FROM app_setting s WHERE s.name = 'region_terms_days_' || c.region)) FROM client c WHERE c.id = client_id)")
    private Integer clientTermsDays;

    /**
     * The workspace's late-interest threshold in US dollars, as stored (the {@code interest_threshold}
     * setting); null when none is set.
     */
    @Formula("(SELECT s.setting_value FROM app_setting s WHERE s.name = 'interest_threshold')")
    private String interestThresholdUsd;

    /**
     * The workspace's plan fee in US dollars, as stored (the {@code plan_fee} setting); null when none
     * is set.
     */
    @Formula("(SELECT s.setting_value FROM app_setting s WHERE s.name = 'plan_fee')")
    private String planFeeUsd;

    /**
     * The workspace's withholding threshold in US dollars, as stored (the {@code withholding_threshold}
     * setting); null when none is set.
     */
    @Formula("(SELECT s.setting_value FROM app_setting s WHERE s.name = 'withholding_threshold')")
    private String withholdingThresholdUsd;

    /**
     * The approval limit in US dollars, as stored: the client's tier's ({@code approval_limit_<tier>} setting)
     * when its tier has one, else the workspace's ({@code approval_limit} setting); null when neither is set.
     */
    @Formula("(SELECT COALESCE((SELECT t.setting_value FROM app_setting t, client c WHERE c.id = client_id AND t.name = 'approval_limit_' || c.tier),"
            + " (SELECT s.setting_value FROM app_setting s WHERE s.name = 'approval_limit')))")
    private String approvalLimitUsd;

    /** The client's own annual late-interest rate, a percentage; null for the workspace's. Read from the client. */
    @Formula("(SELECT c.interest_rate FROM client c WHERE c.id = client_id)")
    private BigDecimal clientInterestRate;

    /**
     * The legal maximum annual late-interest rate of the client's region, a percentage; read from the
     * workspace's regional caps, null when the client has no region or it has no cap.
     */
    @Formula("(SELECT s.setting_value FROM app_setting s, client c WHERE c.id = client_id AND s.name = 'region_max_interest_' || c.region)")
    private String regionMaxInterest;

    /** The early discount the invoice was settled with; 0 when none was taken. */
    @Column(name = "early_discount")
    private BigDecimal earlyDiscount = BigDecimal.ZERO;

    /**
     * The interest of the instalments not yet due that was dropped when the plan was paid off early;
     * 0 when it was not.
     */
    @Column(name = "interest_saved")
    private BigDecimal interestSaved = BigDecimal.ZERO;

    /**
     * The cash rounding the invoice was settled with in cash: what was paid in cash less what was due
     * (signed); 0 when none. It is owed on top of the grand total, so nothing is left due or held as credit.
     */
    @Column(name = "cash_rounding")
    private BigDecimal cashRounding = BigDecimal.ZERO;

    /** An issued credit note's grand total, kept when it is issued; null until then. */
    @Column(name = "credit_total")
    private BigDecimal creditTotal;

    /**
     * What this credit note took off what its original owed when issued; null until then. Any rest
     * of its grand total was held on the client's account as credit.
     */
    @Column(name = "credit_applied")
    private BigDecimal creditApplied;

    /** What the credit notes issued against this invoice took off it; read from them. */
    @Formula("(SELECT COALESCE(SUM(cn.credit_applied), 0) FROM invoice cn WHERE cn.credit_of = id AND cn.status = 'CREDIT_NOTE')")
    private BigDecimal credited = BigDecimal.ZERO;

    /** How many credit notes have been issued against this invoice; read from them. */
    @Formula("(SELECT COUNT(*) FROM invoice cn WHERE cn.credit_of = id AND cn.status = 'CREDIT_NOTE')")
    private int creditNotesIssued;

    /**
     * The credit notes issued against this invoice, as "issue date=amount taken off" joined by commas
     * in the order they were issued; null when there are none. Read from them, for a plan's schedule.
     */
    @Formula("(SELECT LISTAGG(CAST(cn.issue_date AS VARCHAR) || '=' || CAST(cn.credit_applied AS VARCHAR), ',') WITHIN GROUP (ORDER BY cn.issue_date, cn.id) FROM invoice cn WHERE cn.credit_of = id AND cn.status = 'CREDIT_NOTE')")
    private String creditsIssued;

    /**
     * The credit notes issued against this invoice, as "issue date=amount taken off what it owed"
     * joined by commas; null when there are none. Read from them, so that each counts against late
     * interest only from its issue date.
     */
    @Formula("(SELECT LISTAGG(CAST(cn.issue_date AS VARCHAR) || '=' || CAST(COALESCE(cn.credit_applied, 0) AS VARCHAR), ',') WITHIN GROUP (ORDER BY cn.issue_date, cn.id) FROM invoice cn WHERE cn.credit_of = id AND cn.status = 'CREDIT_NOTE')")
    private String creditsOwedTaken;

    /**
     * The late interest charged so far (see {@link #accrueLateInterest}); not stored, worked out again
     * each time the invoice is read.
     */
    @Transient
    private BigDecimal lateInterest = BigDecimal.ZERO;

    /**
     * Today, as the workspace has it (see {@link #priceAsOf}); not stored. A draft is priced and taxed as of
     * this day, since it will be dated today when sent. Null until it is set, when the issue date is used.
     */
    @Transient
    private LocalDate pricedToday;

    /**
     * The late interest written off when they were waived (see {@link #accrueLateInterest}); not
     * stored, worked out again each time the invoice is read.
     */
    @Transient
    private BigDecimal lateWaived = BigDecimal.ZERO;

    /**
     * The late interest each instalment of a plan has been charged (see {@link #accrueLateInterest}), in
     * the order of the instalments; empty when none is. Not stored, worked out again each time the
     * invoice is read.
     */
    @Transient
    private List<BigDecimal> instalmentInterest = List.of();

    /**
     * The late interest each reached milestone has been charged (see {@link #accrueLateInterest}), by the
     * milestone's id; empty when none is. Not stored, worked out again each time the invoice is read.
     */
    @Transient
    private Map<Long, BigDecimal> milestoneInterest = Map.of();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "invoice_id", nullable = false)
    @OrderBy("id")
    private List<InvoiceLine> lines = new ArrayList<>();

    /** The milestones the invoice is billed by, lowest id first; empty when it is billed whole. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @JoinColumn(name = "invoice_id", nullable = false)
    @OrderBy("id")
    private List<Milestone> milestones = new ArrayList<>();

    protected Invoice() {
    }

    /** A new, empty draft for a client, issued and due on the given dates. */
    public static Invoice draft(Long clientId, boolean clientTaxExempt, LocalDate clientTaxExemptUntil, boolean clientPricesIncludeTax, String regionTaxRate,
            String regionRateChanges, boolean regionRoundsDown, String outOfRegionTaxNumber, Currency currency, LocalDate issueDate, LocalDate dueDate) {
        Invoice invoice = new Invoice();
        invoice.clientId = clientId;
        invoice.clientTaxExempt = clientTaxExempt;
        invoice.clientTaxExemptUntil = clientTaxExemptUntil;
        invoice.clientPricesIncludeTax = clientPricesIncludeTax;
        invoice.regionTaxRate = regionTaxRate;
        invoice.regionRateChanges = regionRateChanges;
        invoice.regionRoundsDown = regionRoundsDown;
        invoice.outOfRegionTaxNumber = outOfRegionTaxNumber;
        invoice.clientCurrency = currency.name();
        invoice.status = InvoiceStatus.DRAFT;
        invoice.issueDate = issueDate;
        invoice.dueDate = dueDate;
        return invoice;
    }

    /**
     * A new draft for the same client with copies of this invoice's lines and the same invoice
     * discount, issued and due on the given dates. Payments stay with this invoice.
     */
    public Invoice duplicate(LocalDate issueDate, LocalDate dueDate) {
        Invoice copy = draft(clientId, clientTaxExempt, clientTaxExemptUntil, clientPricesIncludeTax, regionTaxRate, regionRateChanges, regionRoundsDown, outOfRegionTaxNumber, currency(), issueDate, dueDate);
        copy.supplyRegion = supplyRegion;
        copy.discountPct = discountPct;
        copy.discountAmount = discountAmount;
        copy.discountCap = discountCap;
        copy.discountReason = discountReason;
        copy.surchargePct = surchargePct;
        copy.instalments = instalments;
        copy.instalmentRate = instalmentRate;
        copy.planDownPct = planDownPct;
        copy.planFee = planFee;
        lines.forEach(line -> copy.addLine(line.copy()));
        return copy;
    }

    /**
     * The next period's draft of this recurring invoice for the same client, issued and due on the given dates: a copy of
     * each line with a service period, moved to the whole calendar month after the latest period end on the invoice (lines
     * without a period are one-off charges and are not copied), with this invoice's percentage discount and surcharge.
     * The period stops on the end date when it falls inside that month (and is prorated as usual).
     * The draft is recurring too, keeps the end date and remembers it was generated from this invoice.
     */
    public Invoice nextPeriod(LocalDate issueDate, LocalDate dueDate) {
        Invoice next = draft(clientId, clientTaxExempt, clientTaxExemptUntil, clientPricesIncludeTax, regionTaxRate, regionRateChanges, regionRoundsDown, outOfRegionTaxNumber, currency(), issueDate, dueDate);
        next.supplyRegion = supplyRegion;
        next.discountPct = discountPct;
        next.surchargePct = surchargePct;
        next.recurring = true;
        next.recurringFrom = id;
        next.recurUntil = recurUntil;
        LocalDate start = nextPeriodStart();
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        if (recurUntil != null && recurUntil.isBefore(end)) {
            end = recurUntil;
        }
        for (InvoiceLine line : lines) {
            if (line.hasPeriod()) {
                InvoiceLine copy = line.copy();
                copy.setPeriod(start, end);
                next.addLine(copy);
            }
        }
        return next;
    }

    /** The first day of the next period: the first of the month after the latest period end. */
    public LocalDate nextPeriodStart() {
        return latestPeriodEnd().plusMonths(1).withDayOfMonth(1);
    }

    /** The latest service period end of the invoice's lines; null when no line has a period. */
    public LocalDate latestPeriodEnd() {
        return lines.stream().filter(InvoiceLine::hasPeriod).map(InvoiceLine::getPeriodEnd)
                .max(LocalDate::compareTo).orElse(null);
    }

    /** Whether the invoice repeats every period. */
    public boolean isRecurring() {
        return recurring;
    }

    public void setRecurring(boolean recurring) {
        this.recurring = recurring;
    }

    /** The last day the recurring invoice bills for; null when it has no end. */
    public LocalDate getRecurUntil() {
        return recurUntil;
    }

    public void setRecurUntil(LocalDate recurUntil) {
        this.recurUntil = recurUntil;
    }

    /** The id of the recurring invoice this draft was generated from; null when it was not. */
    public Long getRecurringFrom() {
        return recurringFrom;
    }

    /**
     * A new, empty draft credit note for the sent invoice, issued on the given date: same client, no
     * discount, taxed as the original's lines are.
     */
    public Invoice creditNote(LocalDate issueDate) {
        Invoice note = draft(clientId, clientTaxExempt, clientTaxExemptUntil, clientPricesIncludeTax, regionTaxRate, regionRateChanges, regionRoundsDown, outOfRegionTaxNumber, currency(), issueDate, null);
        note.creditOf = id;
        note.supplyRegion = supplyRegion;
        note.creditedIssueDate = this.issueDate;
        note.creditedSurchargePct = this.surchargePct;
        return note;
    }

    /** Tells the invoice what today is, the day a draft is priced and taxed as of (see {@link #pricingDate}). */
    public void priceAsOf(LocalDate today) {
        this.pricedToday = today;
    }

    /**
     * The day the invoice's prices, exchange rates, monthly volume and tax are taken as of: today for a draft
     * (not a credit note), since it will be dated today when sent; otherwise its issue date.
     */
    public LocalDate pricingDate() {
        return isDraft() && !isCreditNote() && pricedToday != null ? pricedToday : issueDate;
    }

    /** The day the invoice is taxed as of: a credit note's original's issue date, otherwise {@link #pricingDate}. */
    private LocalDate taxDate() {
        return creditOf != null ? creditedIssueDate : pricingDate();
    }

    /** The currency the invoice is in: its client's. */
    public Currency currency() {
        return Currency.orUsd(clientCurrency);
    }

    /**
     * How many decimals its amounts have: 2, to the cent, or 0 for whole yen. Wherever an amount is
     * rounded to the cent it is rounded to this instead.
     */
    public int scale() {
        return currency().scale();
    }

    /** Nothing, in the invoice's currency. */
    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(scale());
    }

    /**
     * The US dollars one unit of the invoice's currency is worth on the date: the latest dated rate on
     * or before it, otherwise the standing rate (at par when none is set, or when there is no date).
     */
    private BigDecimal usdPerOn(LocalDate on) {
        if (currency() == Currency.USD) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = usdPer == null ? BigDecimal.ONE : new BigDecimal(usdPer.trim());
        if (datedRates == null || on == null) {
            return rate;
        }
        for (String entry : datedRates.split(",")) {
            String[] parts = entry.split("=");
            if (LocalDate.parse(parts[0].trim()).isAfter(on)) {
                break;
            }
            rate = new BigDecimal(parts[1].trim());
        }
        return rate;
    }

    /**
     * Whether what is left is small enough to write off (see {@link #isSmallEnoughToWriteOff(BigDecimal,
     * LocalDate, BigDecimal)}) at the workspace rate on the day of the latest payment.
     */
    private boolean isSmallEnoughToWriteOff(BigDecimal left, BigDecimal taxRate) {
        return isSmallEnoughToWriteOff(left, lastPaidOn, taxRate);
    }

    /**
     * Whether what is left is worth less than {@link #WRITE_OFF_LIMIT} US dollars at the rate on the date
     * and is no more than {@link #WRITE_OFF_SHARE} of the grand total at the tax rate.
     */
    private boolean isSmallEnoughToWriteOff(BigDecimal left, LocalDate on, BigDecimal taxRate) {
        return left.multiply(usdPerOn(on)).compareTo(WRITE_OFF_LIMIT) < 0
                && left.compareTo(owedTotal(taxRate).multiply(WRITE_OFF_SHARE)) <= 0;
    }

    /**
     * The exchange difference in US dollars of an amount applied to the invoice and paid on the date:
     * its dollar value at the rate on the date less its dollar value at the rate on the issue date, each
     * rounded half up to the cent (a loss is negative). Nothing for a dollar invoice.
     */
    public BigDecimal fxDifference(BigDecimal applied, LocalDate paidOn) {
        BigDecimal paid = applied.multiply(usdPerOn(paidOn)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal issued = applied.multiply(usdPerOn(issueDate)).setScale(2, RoundingMode.HALF_UP);
        return paid.subtract(issued);
    }

    /**
     * The exchange difference in US dollars of every payment (not reversed) applied to the invoice
     * (see {@link #fxDifference(BigDecimal, LocalDate)}), added up.
     */
    public BigDecimal fxDifference(List<Payment> payments) {
        return payments.stream().filter(p -> !p.isReversed())
                .map(p -> fxDifference(p.amountTo(id), p.getDate()))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Whether this is a credit note against another invoice. */
    public boolean isCreditNote() {
        return creditOf != null;
    }

    /** The id of the invoice this credit note credits; null for an ordinary invoice. */
    public Long getCreditOf() {
        return creditOf;
    }

    /** The number shown: CN-... for a credit note, otherwise INV-... (see {@link InvoiceNumber}). */
    public String number() {
        return isCreditNote() ? InvoiceNumber.creditNoteOf(id) : InvoiceNumber.of(id);
    }

    /** What the credit notes issued against this invoice add up to, to the cent. */
    public BigDecimal getCredited() {
        return (credited == null ? BigDecimal.ZERO : credited).setScale(scale(), RoundingMode.HALF_UP);
    }

    /** Whether a credit note has been issued against this invoice: it can then no longer change. */
    public boolean hasCreditNotesIssued() {
        return creditNotesIssued > 0;
    }

    /**
     * Issues a draft credit note on the given date, keeping its grand total at the tax rate and what
     * its original took of it.
     */
    public void markIssued(LocalDate issueDate, BigDecimal taxRate, BigDecimal applied) {
        this.creditTotal = total(taxRate);
        this.creditApplied = applied;
        this.status = InvoiceStatus.CREDIT_NOTE;
        this.issueDate = issueDate;
    }

    public Long getId() {
        return id;
    }

    public Long getClientId() {
        return clientId;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    /** The percentage taken off the subtotal, 0 to 100 with up to two decimals. */
    public BigDecimal getDiscountPct() {
        return discountPct;
    }

    /** The fixed amount taken off the subtotal, to the cent; 0 when the discount is a percentage. */
    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    /** The most taken off the invoice in all, line discounts included, to the cent; null when there is no cap. */
    public BigDecimal getDiscountCap() {
        return discountCap;
    }

    /** Why the discount was given; null when no reason was given. */
    public String getDiscountReason() {
        return discountReason;
    }

    /** Sets why the discount was given; blank is no reason. */
    public void setDiscountReason(String discountReason) {
        this.discountReason = discountReason == null || discountReason.isBlank() ? null : discountReason.strip();
    }

    /** Whether the discount takes off more than a fifth of the subtotal, after any cap, and so needs approval. */
    public boolean discountIsLarge() {
        return discount().multiply(BigDecimal.valueOf(5)).compareTo(subtotal()) > 0;
    }

    /** Sets the percentage taken off the subtotal, clearing any fixed amount; it has already been checked. */
    public void setDiscountPct(BigDecimal discountPct) {
        this.discountPct = discountPct;
        this.discountAmount = BigDecimal.ZERO;
    }

    /** Sets the most taken off the invoice in all, or null for no cap; it has already been checked as money. */
    public void setDiscountCap(BigDecimal discountCap) {
        this.discountCap = discountCap;
    }

    /**
     * Sets a fixed amount taken off the subtotal, clearing any percentage; it has already been checked
     * as money. An amount over the subtotal of the lines it is taken off (see {@link #discountableSubtotal()}) is refused.
     */
    public void setDiscountAmount(BigDecimal discountAmount) {
        BigDecimal discountable = discountableSubtotal();
        if (discountAmount.compareTo(discountable) > 0) {
            throw new InvoiceRejectedException("The discount cannot be more than the subtotal of "
                    + currency().format(discountable)
                    + (discountable.compareTo(subtotal()) == 0 ? "" : " of the lines not priced from a rate card or the price list") + ".");
        }
        this.discountAmount = discountAmount;
        this.discountPct = BigDecimal.ZERO;
    }

    /** The rush surcharge, a percentage of the amount after the discounts, 0 to {@link #MAX_SURCHARGE_PCT}. */
    public BigDecimal getSurchargePct() {
        return surchargePct;
    }

    /** The surcharge percentage charged: a credit note's is its original's; otherwise its own. */
    public BigDecimal chargedSurchargePct() {
        if (isCreditNote()) {
            return creditedSurchargePct == null ? BigDecimal.ZERO : creditedSurchargePct;
        }
        return surchargePct;
    }

    /** Sets the rush surcharge percentage; it has already been checked. */
    public void setSurchargePct(BigDecimal surchargePct) {
        this.surchargePct = surchargePct;
    }

    /** The day the late interest still unpaid was last waived; null when never. */
    public LocalDate getInterestWaivedOn() {
        return interestWaivedOn;
    }

    /** Waives the late interest still unpaid on the given day; interest starts again from then. */
    public void waiveInterest(LocalDate on) {
        this.interestWaivedOn = on;
    }

    /** The day the client disputed the invoice; null when it has never been disputed. */
    public LocalDate getDisputedOn() {
        return disputedOn;
    }

    /** The day the dispute was resolved; null while it is open or when there has been none. */
    public LocalDate getDisputeResolvedOn() {
        return disputeResolvedOn;
    }

    /**
     * Whether the invoice is in dispute: what is in dispute (see {@link #disputedAmount}) earns no interest
     * and is not overdue until the dispute is resolved.
     */
    public boolean isDisputed() {
        return disputedOn != null && disputeResolvedOn == null;
    }

    /** Whether the dispute is over particular lines rather than the whole invoice. */
    public boolean hasDisputedLines() {
        return lines.stream().anyMatch(InvoiceLine::isDisputed);
    }

    /**
     * Whether the whole invoice is in dispute (an open dispute over no particular lines): it then takes no
     * payments until the dispute is resolved.
     */
    public boolean isWhollyDisputed() {
        return isDisputed() && !hasDisputedLines();
    }

    /** Puts the whole invoice in dispute from the given day. */
    public void dispute(LocalDate on) {
        lines.forEach(l -> l.setDisputed(false));
        this.disputedOn = on;
        this.disputeResolvedOn = null;
        this.disputeCredit = false;
    }

    /**
     * Puts the line in dispute: it joins an open dispute over other lines, or starts a new dispute from the
     * given day (the lines of an earlier, resolved dispute leave it).
     */
    public void disputeLine(InvoiceLine line, LocalDate on) {
        if (!isDisputed()) {
            lines.forEach(l -> l.setDisputed(false));
            this.disputedOn = on;
            this.disputeResolvedOn = null;
            this.disputeCredit = false;
        }
        line.setDisputed(true);
    }

    /**
     * What the disputed lines come to at the tax rate: each line's amount after its share of the invoice
     * discount, with its tax.
     */
    public BigDecimal disputedLinesAmount(BigDecimal taxRate) {
        List<BigDecimal> shares = discountShares();
        List<BigDecimal> taxes = lineTaxes(taxRate);
        List<BigDecimal> inside = lineTaxesInside(taxRate);
        BigDecimal amount = zero();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isDisputed()) {
                continue;
            }
            BigDecimal line = lines.get(i).amount(scale()).subtract(shares.get(i));
            if (!clientPricesIncludeTax) {
                line = line.add(taxes.get(i));
            } else if (isReverseCharge()) {
                line = line.subtract(inside.get(i));
            }
            amount = amount.add(line);
        }
        return amount;
    }

    /**
     * Whether, once the given amount has been paid, all that is still due is in dispute, so the invoice takes
     * no payments until the dispute is resolved.
     */
    public boolean onlyDisputedDue(BigDecimal paid, BigDecimal taxRate) {
        return isDisputed() && amountDue(paid, taxRate).subtract(disputedAmount(paid, taxRate)).signum() <= 0;
    }

    /**
     * What is in dispute at the tax rate once the given amount has been paid: nothing when there is no open
     * dispute; all that is due when the whole invoice is disputed; otherwise the disputed lines (see
     * {@link #disputedLinesAmount}), but never more than is due — payments go to the rest of the invoice first.
     */
    public BigDecimal disputedAmount(BigDecimal paid, BigDecimal taxRate) {
        if (!isDisputed() || isCreditNote() || isWrittenOff()) {
            return zero();
        }
        BigDecimal due = amountDue(paid, taxRate).max(zero());
        return isWhollyDisputed() ? due : disputedLinesAmount(taxRate).min(due);
    }

    /**
     * Resolves the dispute on the given day: from then on the dispute holds no interest back, so the invoice
     * owes the interest it would have earned had it never been disputed, the days in dispute included.
     */
    public void resolveDispute(LocalDate on) {
        this.disputeResolvedOn = on;
        this.disputeCredit = false;
    }

    /**
     * Resolves the dispute on the given day with a credit note for what was in dispute: the interest the
     * dispute held back on the disputed amount while it was open is never charged.
     */
    public void resolveDisputeWithCredit(LocalDate on) {
        this.disputeResolvedOn = on;
        this.disputeCredit = true;
    }

    /** Whether the dispute was resolved with a credit note (see {@link #resolveDisputeWithCredit}). */
    public boolean isDisputeCredit() {
        return disputeCredit;
    }

    /**
     * The lines a credit note settling the open dispute credits: the disputed lines, or all of them when the
     * whole invoice is in dispute.
     */
    public List<InvoiceLine> disputedLines() {
        return isWhollyDisputed() ? List.copyOf(lines) : lines.stream().filter(InvoiceLine::isDisputed).toList();
    }

    /**
     * Whether the dispute holds interest back on the disputed amount: while it is open, and for the days it was
     * open when it was resolved with a credit note.
     */
    private boolean holdsBackInterest() {
        return disputedOn != null && (disputeResolvedOn == null || disputeCredit);
    }

    /** The day the dispute stopped holding interest back, no later than the given day. */
    private LocalDate holdBackEnd(LocalDate to) {
        return disputeResolvedOn == null || disputeResolvedOn.isAfter(to) ? to : disputeResolvedOn;
    }

    /** The day the invoice was written off as a bad debt; null when it was not. */
    public LocalDate getWrittenOffOn() {
        return writtenOffOn;
    }

    /** Whether the invoice was written off as a bad debt: it then owes nothing and takes no payments. */
    public boolean isWrittenOff() {
        return writtenOffOn != null;
    }

    /**
     * Writes the invoice off as a bad debt on the given day: everything still owed on it then, late
     * charges included, is written off, and it stops earning interest.
     */
    public void writeOff(LocalDate on) {
        this.writtenOffOn = on;
    }

    /**
     * How much of a credit note of the amount comes off this invoice once the given amount has been
     * paid, at the tax rate: what it still owes; the rest is left over as credit.
     */
    public BigDecimal creditTaken(BigDecimal amount, BigDecimal paid, BigDecimal taxRate) {
        if (hasPlan()) {
            // It comes off the plan's instalments to come instead (see #schedule).
            return amount;
        }
        return amount.min(amountDue(paid, taxRate).max(BigDecimal.ZERO));
    }

    /**
     * The withholding: the client's withholding percentage of the amount before tax, rounded down to
     * the cent. The client pays it to its tax office instead, so it is never owed. Nothing for a credit
     * note, nor for a reverse-charged invoice, nor for one whose amount before tax is under the
     * workspace's withholding threshold: the client pays it all.
     */
    public BigDecimal withholding(BigDecimal taxRate) {
        if (isCreditNote() || isReverseCharge() || withholdingPct == null || withholdingPct.signum() <= 0) {
            return zero();
        }
        BigDecimal beforeTax = beforeTax(taxRate);
        if (beforeTax.compareTo(withholdingThreshold()) < 0) {
            return zero();
        }
        return beforeTax.multiply(withholdingPct).divide(BigDecimal.valueOf(100))
                .setScale(scale(), RoundingMode.DOWN);
    }

    /** What the client is to pay of the grand total at the tax rate: the grand total less the withholding. */
    public BigDecimal owedTotal(BigDecimal taxRate) {
        return total(taxRate).subtract(withholding(taxRate));
    }

    /**
     * What the invoice is owed in all: the grand total (the plan total when it is spread over
     * instalments) less what is credited, plus the late interest charged.
     */
    private BigDecimal payable(BigDecimal taxRate) {
        return payableBeforeInterest(taxRate).add(getLateInterest());
    }

    /**
     * As {@link #payable}, but without the late interest charged. A plan's credit notes have
     * already come off its plan total.
     */
    private BigDecimal payableBeforeInterest(BigDecimal taxRate) {
        BigDecimal owed = hasPlan() ? planTotal(taxRate)
                : (isBilledByMilestones() ? reachedTotal(taxRate) : owedTotal(taxRate)).subtract(getCredited());
        return owed.subtract(getEarlyDiscountTaken())
                .subtract(getInterestSaved()).add(getCashRounding());
    }

    /** The late interest charged, to the cent; 0 when none is. */
    public BigDecimal getLateInterest() {
        return lateInterest.setScale(scale(), RoundingMode.HALF_UP);
    }

    /** The late interest written off by waiving it, to the cent; 0 when none was. */
    public BigDecimal getLateWaived() {
        return lateWaived.setScale(scale(), RoundingMode.HALF_UP);
    }

    /**
     * Works out the late interest charged by the given day, at the annual rate (a percentage), from the
     * payments (those not reversed count), and keeps it as part of what is payable. No late fee is
     * charged. A sent invoice not
     * spread over instalments, with something still owed more than {@link #graceDays()} days after its
     * due date, earns interest from the end of its grace period ({@link #graceDays()} days after its due
     * date), compounded daily at rate / 360 (Actual/360): a balance B owing for
     * d days earns B x ((1 + rate / 100 / 360)^d - 1), rounded half up to the cent. Each payment after
     * the grace period ends a period; every period earns interest on what the invoice still owed during it
     * (earlier interest left out), and a payment pays the invoice, then the interest earned
     * so far. A credit note issued after the grace period ends a period too:
     * what it took off what the invoice owed counts only from its issue date, so the periods before it
     * earn interest on what was owed before the credit. An invoice spread over instalments instead earns interest
     * instalment by instalment (see {@link #accrueInstalmentInterest}), none of it charged until the
     * instalments' interest together reaches {@link #interestThreshold()}. On the day the
     * interest was waived (after the day's payments), the interest still unpaid is written off
     * (kept as what was waived) and that day ends a period: interest starts again from it on what is
     * still owed. A written-off invoice is charged only up to the day it was
     * written off.
     */
    public void accrueLateInterest(List<Payment> payments, BigDecimal taxRate, BigDecimal annualRate, LocalDate asOf) {
        annualRate = lateInterestRate(annualRate);
        LocalDate today = writtenOffOn != null && writtenOffOn.isBefore(asOf) ? writtenOffOn : asOf;
        this.lateInterest = BigDecimal.ZERO;
        this.lateWaived = BigDecimal.ZERO;
        this.instalmentInterest = List.of();
        this.milestoneInterest = Map.of();
        if (status == InvoiceStatus.SENT && !isCreditNote() && isBilledByMilestones()) {
            accrueMilestoneInterest(payments, taxRate, annualRate, today);
            if (getLateInterest().compareTo(interestThreshold()) < 0) {
                this.lateInterest = BigDecimal.ZERO;
                this.milestoneInterest = Map.of();
            }
            return;
        }
        if (status == InvoiceStatus.SENT && !isCreditNote() && hasPlan()) {
            accrueInstalmentInterest(payments, taxRate, annualRate, today);
            if (getLateInterest().compareTo(interestThreshold()) < 0) {
                this.lateInterest = BigDecimal.ZERO;
                this.instalmentInterest = List.of();
            }
            return;
        }
        accrueInvoiceInterest(payments, taxRate, annualRate, today);
        if (getLateInterest().add(getLateWaived()).compareTo(interestThreshold()) < 0) {
            this.lateInterest = BigDecimal.ZERO;
            this.lateWaived = BigDecimal.ZERO;
        }
    }

    /**
     * The late interest an invoice (all of a plan's instalments together) must add up to before any of it is charged: the
     * workspace's threshold in US dollars converted at the issue date's rate, rounded down to the step;
     * 0 when none is set.
     */
    private BigDecimal interestThreshold() {
        if (interestThresholdUsd == null) {
            return zero();
        }
        return currency().divideDownToStep(new BigDecimal(interestThresholdUsd.trim()), usdPerOn(issueDate));
    }

    /**
     * The amount before tax an invoice must reach before anything is withheld: the workspace's threshold
     * in US dollars converted at the issue date's rate, rounded down to the step; 0 when none is set.
     */
    private BigDecimal withholdingThreshold() {
        if (withholdingThresholdUsd == null) {
            return zero();
        }
        return currency().divideDownToStep(new BigDecimal(withholdingThresholdUsd.trim()), usdPerOn(issueDate));
    }

    /** Whether an invoice needs approval before it is sent, has been approved, or needs none. */
    public enum Approval {
        NOT_NEEDED, NEEDS_APPROVAL, APPROVED
    }

    /**
     * The grand total above which a draft needs approval: the approval limit (the client tier's, else the workspace's) in US dollars
     * converted at the issue date's rate, rounded down to the step; null when none is set.
     */
    private BigDecimal approvalLimit() {
        if (approvalLimitUsd == null) {
            return null;
        }
        return currency().divideDownToStep(new BigDecimal(approvalLimitUsd.trim()), usdPerOn(issueDate));
    }

    /** The number of days an approval stays good after the day it was given. */
    public static final int APPROVAL_DAYS = 30;

    /**
     * The invoice's approval at the tax rate as of today: a credit note, or an invoice whose grand total
     * (before any withholding) is not over the approval limit and whose discount takes off no more than a
     * fifth of the subtotal, needs none; otherwise it is approved while its grand total is exactly the total
     * it was approved at and no more than {@link #APPROVAL_DAYS} days have passed since it was approved.
     * Once sent, an invoice is as it was approved. Every draft of a client with a HIGH payment risk (highRisk)
     * needs approval, whatever its size.
     */
    public Approval approval(BigDecimal taxRate, LocalDate today, boolean highRisk) {
        if (isCreditNote()) {
            return Approval.NOT_NEEDED;
        }
        if (!isDraft()) {
            return approvedTotal == null ? Approval.NOT_NEEDED : Approval.APPROVED;
        }
        BigDecimal limit = approvalLimit();
        BigDecimal total = total(taxRate);
        if (!highRisk && !discountIsLarge() && (limit == null || total.compareTo(limit) <= 0)) {
            return Approval.NOT_NEEDED;
        }
        if (approvedTotal == null || total.compareTo(approvedTotal) != 0) {
            return Approval.NEEDS_APPROVAL;
        }
        boolean lapsed = approvedOn != null && today.isAfter(approvedOn.plusDays(APPROVAL_DAYS));
        return lapsed ? Approval.NEEDS_APPROVAL : Approval.APPROVED;
    }

    public BigDecimal getApprovedTotal() {
        return approvedTotal;
    }

    public LocalDate getApprovedOn() {
        return approvedOn;
    }

    /** Approves the invoice at the grand total on the day. */
    public void approve(BigDecimal total, LocalDate on) {
        this.approvedTotal = total;
        this.approvedOn = on;
    }

    /** The late interest charged on an invoice not spread over instalments (see {@link #accrueLateInterest}). */
    private void accrueInvoiceInterest(List<Payment> payments, BigDecimal taxRate, BigDecimal annualRate,
            LocalDate today) {
        if (status != InvoiceStatus.SENT || isCreditNote() || hasPlan() || dueDate == null
                || !today.isAfter(dueDate.plusDays(graceDays()))) {
            return;
        }
        if (annualRate == null || annualRate.signum() <= 0) {
            return;
        }
        record Part(LocalDate on, Long paymentId, BigDecimal amount, boolean credit) {
        }
        List<Part> inOrder = new ArrayList<>();
        payments.stream().filter(p -> !p.isReversed())
                .flatMap(p -> p.getAllocations().stream().filter(a -> a.getInvoiceId().equals(id))
                        .map(a -> new Part(a.getDate() == null ? p.getDate() : a.getDate(), p.getId(), a.getAmount(), false)))
                .filter(p -> !p.on().isAfter(today))
                .forEach(inOrder::add);
        // What a credit note took off what the invoice owed counts only from its issue date: until then
        // the invoice still owed it. One issued during the grace period, or later than today, comes off
        // from the start.
        BigDecimal owed = payableBeforeInterest(taxRate);
        if (creditsOwedTaken != null && !creditsOwedTaken.isBlank()) {
            for (String credit : creditsOwedTaken.split(",")) {
                String[] parts = credit.split("=");
                LocalDate on = LocalDate.parse(parts[0].trim());
                BigDecimal amount = new BigDecimal(parts[1].trim());
                if (amount.signum() > 0 && on.isAfter(dueDate.plusDays(graceDays())) && !on.isAfter(today)) {
                    owed = owed.add(amount);
                    inOrder.add(new Part(on, Long.MIN_VALUE, amount, true));
                }
            }
        }
        inOrder.sort(Comparator.comparing(Part::on).thenComparing(Part::paymentId));
        BigDecimal paidInGrace = inOrder.stream().filter(p -> !p.on().isAfter(dueDate.plusDays(graceDays())))
                .map(Part::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (owed.subtract(paidInGrace).signum() <= 0) {
            return;
        }
        LocalDate overdueOn = dueDate.plusDays(graceDays() + 1);
        BigDecimal disputedLines = hasDisputedLines() ? disputedLinesAmount(taxRate) : null;
        BigDecimal daily = BigDecimal.ONE.add(annualRate.divide(BigDecimal.valueOf(36000), MathContext.DECIMAL128));
        BigDecimal charged = BigDecimal.ZERO;
        BigDecimal unpaidInterest = BigDecimal.ZERO;
        LocalDate from = dueDate.plusDays(graceDays());
        LocalDate waiveOn = interestWaivedOn != null && !interestWaivedOn.isBefore(overdueOn)
                && !interestWaivedOn.isAfter(today) ? interestWaivedOn : null;
        BigDecimal interestWaived = BigDecimal.ZERO;
        for (Part part : inOrder) {
            if (waiveOn != null && part.on().isAfter(waiveOn)) {
                if (owed.signum() > 0) {
                    BigDecimal earned = earnedOn(owed, disputedLines, daily, from, waiveOn);
                    charged = charged.add(earned);
                    unpaidInterest = unpaidInterest.add(earned);
                }
                from = waiveOn;
                interestWaived = unpaidInterest;
                unpaidInterest = BigDecimal.ZERO;
                waiveOn = null;
            }
            if (part.on().isAfter(from) && owed.signum() > 0) {
                BigDecimal earned = earnedOn(owed, disputedLines, daily, from, part.on());
                charged = charged.add(earned);
                unpaidInterest = unpaidInterest.add(earned);
                from = part.on();
            }
            if (part.credit()) {
                owed = owed.subtract(part.amount());
                continue;
            }
            BigDecimal toOwed = part.amount().min(owed).max(BigDecimal.ZERO);
            BigDecimal rest = part.amount().subtract(toOwed);
            BigDecimal toInterest = rest.min(unpaidInterest).max(BigDecimal.ZERO);
            unpaidInterest = unpaidInterest.subtract(toInterest);
            owed = owed.subtract(toOwed).subtract(rest.subtract(toInterest));
        }
        if (waiveOn != null) {
            if (owed.signum() > 0) {
                BigDecimal earned = earnedOn(owed, disputedLines, daily, from, waiveOn);
                charged = charged.add(earned);
                unpaidInterest = unpaidInterest.add(earned);
            }
            from = waiveOn;
            interestWaived = unpaidInterest;
        }
        if (owed.signum() > 0) {
            charged = charged.add(earnedOn(owed, disputedLines, daily, from, today));
        }
        this.lateInterest = charged.subtract(interestWaived);
        this.lateWaived = interestWaived;
    }

    /**
     * Works out the late interest each instalment of the plan has been charged by the given
     * day, at the annual rate (a percentage), from the payments (those not
     * reversed count; what credit notes took off has already come off the schedule). Payments, in the
     * order they were made, pay each instalment itself and then its interest earned
     * so far, earliest instalment first. An instalment still not paid off more than
     * {@link #graceDays()} days after its due date is charged no late fee, and earns interest by the
     * same rule as an invoice: from the end of its own grace period, compounded daily at rate / 360 (Actual/360), on
     * what is still unpaid of it, with each payment after its grace period ending a period.
     */
    private void accrueInstalmentInterest(List<Payment> payments, BigDecimal taxRate, BigDecimal annualRate,
            LocalDate today) {
        if (annualRate == null || annualRate.signum() <= 0) {
            return;
        }
        record Part(LocalDate on, Long paymentId, BigDecimal amount) {
        }
        List<Part> inOrder = new ArrayList<>();
        payments.stream().filter(p -> !p.isReversed())
                .flatMap(p -> p.getAllocations().stream().filter(a -> a.getInvoiceId().equals(id))
                        .map(a -> new Part(a.getDate() == null ? p.getDate() : a.getDate(), p.getId(), a.getAmount())))
                .filter(p -> !p.on().isAfter(today))
                .forEach(inOrder::add);
        inOrder.sort(Comparator.comparing(Part::on).thenComparing(Part::paymentId));
        if (getInterestSaved().signum() > 0 && !inOrder.isEmpty()) {
            // The interest dropped by paying the plan off counts as paid with the payment that settled it.
            inOrder.add(new Part(inOrder.get(inOrder.size() - 1).on(), Long.MAX_VALUE, getInterestSaved()));
        }
        List<Instalment> schedule = schedule(taxRate);
        List<BigDecimal> charges = chargesInOrder(schedule.stream().map(Instalment::dueDate).toList(),
                schedule.stream().map(Instalment::payment).toList(), inOrder.stream().map(Part::on).toList(),
                inOrder.stream().map(Part::amount).toList(), annualRate, today);
        BigDecimal total = charges.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        charges = charges.stream().map(c -> c.setScale(scale(), RoundingMode.HALF_UP)).toList();
        this.instalmentInterest = charges;
        this.lateInterest = total;
    }

    /**
     * Works out the late interest each reached milestone has been charged by the given day, at the annual
     * rate (a percentage), by the same rule as a plan's instalments (see {@link #accrueInstalmentInterest}):
     * each reached milestone falls due on its own (see {@link #milestoneDueDate}), and payments (those not
     * reversed, with what credit notes took off counting from the start) pay the reached milestones in
     * due-date order (then id), each milestone's amount and then its interest earned so far. A milestone
     * still not paid off more than {@link #graceDays()} days after its due date earns interest from the end
     * of its own grace period on what is still unpaid of it.
     */
    private void accrueMilestoneInterest(List<Payment> payments, BigDecimal taxRate, BigDecimal annualRate,
            LocalDate today) {
        if (annualRate == null || annualRate.signum() <= 0) {
            return;
        }
        record Part(LocalDate on, Long paymentId, BigDecimal amount) {
        }
        List<Part> inOrder = new ArrayList<>();
        if (getCredited().signum() > 0) {
            inOrder.add(new Part(LocalDate.MIN, Long.MIN_VALUE, getCredited()));
        }
        payments.stream().filter(p -> !p.isReversed())
                .flatMap(p -> p.getAllocations().stream().filter(a -> a.getInvoiceId().equals(id))
                        .map(a -> new Part(a.getDate() == null ? p.getDate() : a.getDate(), p.getId(), a.getAmount())))
                .filter(p -> !p.on().isAfter(today))
                .forEach(inOrder::add);
        inOrder.sort(Comparator.comparing(Part::on).thenComparing(Part::paymentId));
        List<BigDecimal> amounts = milestoneAmounts(taxRate);
        List<Integer> order = reachedInDueOrder();
        List<BigDecimal> charges = chargesInOrder(order.stream().map(k -> milestoneDueDate(milestones.get(k))).toList(),
                order.stream().map(amounts::get).toList(), inOrder.stream().map(Part::on).toList(),
                inOrder.stream().map(Part::amount).toList(), annualRate, today);
        Map<Long, BigDecimal> byMilestone = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int n = 0; n < order.size(); n++) {
            byMilestone.put(milestones.get(order.get(n)).getId(), charges.get(n).setScale(scale(), RoundingMode.HALF_UP));
            total = total.add(charges.get(n));
        }
        this.milestoneInterest = byMilestone;
        this.lateInterest = total;
    }

    /**
     * The late interest each of a run of amounts falling due on the given days has been charged by the given
     * day, at the annual rate (a percentage), in their order, not yet rounded. The parts paid (on the given
     * days, in that order) pay each amount itself and then its interest earned so far, earliest first. An
     * amount still not paid off more than {@link #graceDays()} days after its due date earns interest from
     * the end of its grace period, compounded daily at rate / 360 (Actual/360), on what is still unpaid of it,
     * with each payment after its grace period ending a period.
     */
    private List<BigDecimal> chargesInOrder(List<LocalDate> dues, List<BigDecimal> amounts, List<LocalDate> paidOn,
            List<BigDecimal> paidAmounts, BigDecimal annualRate, LocalDate today) {
        BigDecimal[] left = paidAmounts.toArray(BigDecimal[]::new);
        BigDecimal daily = BigDecimal.ONE.add(annualRate.divide(BigDecimal.valueOf(36000), MathContext.DECIMAL128));
        List<BigDecimal> charges = new ArrayList<>();
        for (int k = 0; k < dues.size(); k++) {
            LocalDate due = dues.get(k);
            BigDecimal owed = amounts.get(k);
            boolean late = false;
            if (due != null && today.isAfter(due.plusDays(graceDays()))) {
                BigDecimal paidInGrace = BigDecimal.ZERO;
                for (int j = 0; j < left.length; j++) {
                    if (!paidOn.get(j).isAfter(due.plusDays(graceDays()))) {
                        paidInGrace = paidInGrace.add(left[j]);
                    }
                }
                late = paidInGrace.compareTo(owed) < 0;
            }
            BigDecimal charged = BigDecimal.ZERO;
            BigDecimal unpaidInterest = BigDecimal.ZERO;
            LocalDate from = due == null ? null : due.plusDays(graceDays());
            for (int j = 0; j < left.length; j++) {
                LocalDate on = paidOn.get(j);
                if (late && on.isAfter(from) && owed.signum() > 0) {
                    BigDecimal earned = interestOn(owed, daily, interestDays(from, on));
                    charged = charged.add(earned);
                    unpaidInterest = unpaidInterest.add(earned);
                    from = on;
                }
                BigDecimal toOwed = left[j].min(owed).max(BigDecimal.ZERO);
                owed = owed.subtract(toOwed);
                left[j] = left[j].subtract(toOwed);
                BigDecimal toInterest = left[j].min(unpaidInterest).max(BigDecimal.ZERO);
                unpaidInterest = unpaidInterest.subtract(toInterest);
                left[j] = left[j].subtract(toInterest);
            }
            if (late && owed.signum() > 0) {
                charged = charged.add(interestOn(owed, daily, interestDays(from, today)));
            }
            charges.add(charged);
        }
        return charges;
    }

    /**
     * The interest what the invoice owes earns from one day to another at the daily growth factor. While
     * disputed lines are in an open dispute (from the day they were disputed) only what is owed besides them
     * (disputedLines) earns interest, the days before earning on all that is owed; each part is rounded half up
     * to the cent. Once the dispute is resolved it holds nothing back: all that is owed earns interest as if it
     * had never been disputed, the days in dispute included — unless it was resolved with a credit note, when
     * the days it was open still hold the disputed lines back. With no disputed lines (null), the days the whole
     * invoice spends in an open dispute earn nothing (see {@link #interestDays}).
     */
    private BigDecimal earnedOn(BigDecimal owed, BigDecimal disputedLines, BigDecimal daily, LocalDate from, LocalDate to) {
        if (disputedLines == null || !holdsBackInterest()) {
            return interestOn(owed, daily, interestDays(from, to));
        }
        LocalDate start = from.isAfter(disputedOn) ? from : disputedOn;
        LocalDate end = holdBackEnd(to);
        if (!start.isBefore(end)) {
            return interestOn(owed, daily, ChronoUnit.DAYS.between(from, to));
        }
        return interestOn(owed, daily, ChronoUnit.DAYS.between(from, start))
                .add(interestOn(owed.subtract(disputedLines).max(BigDecimal.ZERO), daily, ChronoUnit.DAYS.between(start, end)))
                .add(interestOn(owed, daily, ChronoUnit.DAYS.between(end, to)));
    }

    /**
     * The days from one day to another that earn interest: those the whole invoice spends in an open dispute
     * (from the day it was disputed) earn none. A resolved dispute takes no days off — the interest it held
     * back is owed — unless it was resolved with a credit note, when its days still earn none; nor does a
     * dispute over particular lines (see {@link #earnedOn}).
     */
    private long interestDays(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        if (!holdsBackInterest() || days <= 0 || hasDisputedLines()) {
            return days;
        }
        LocalDate start = from.isAfter(disputedOn) ? from : disputedOn;
        return days - Math.max(0, ChronoUnit.DAYS.between(start, holdBackEnd(to)));
    }

    /** The interest the balance earns over the days at the daily growth factor, rounded half up to the cent. */
    private BigDecimal interestOn(BigDecimal balance, BigDecimal daily, long days) {
        if (days <= 0) {
            return zero();
        }
        return balance.multiply(daily.pow((int) days, MathContext.DECIMAL128).subtract(BigDecimal.ONE))
                .setScale(scale(), RoundingMode.HALF_UP);
    }

    /**
     * The early-payment discount on offer: the client's early-payment percentage of the amount before
     * tax (the subtotal less the discount), rounded down to the cent; nothing for a credit note, or an invoice
     * spread over instalments.
     */
    public BigDecimal earlyDiscount(BigDecimal taxRate) {
        if (isCreditNote() || hasPlan() || earlyPayDays == null
                || earlyPayPct == null || earlyPayPct.signum() <= 0) {
            return zero();
        }
        return beforeTax(taxRate).multiply(earlyPayPct).divide(BigDecimal.valueOf(100)).setScale(scale(), RoundingMode.DOWN);
    }

    /**
     * Whether a sent invoice not yet settled early can still be paid off early on the given day: within
     * the client's early-payment days of its issue date, with an early discount on offer.
     */
    public boolean isEarlyWindowOpen(LocalDate on, BigDecimal taxRate) {
        return status == InvoiceStatus.SENT && issueDate != null && getEarlyDiscountTaken().signum() == 0
                && earlyDiscount(taxRate).signum() > 0 && !on.isAfter(issueDate.plusDays(earlyPayDays));
    }

    /**
     * What would settle the invoice on the given day once the given amount has been paid: what is due
     * less the early discount while the early window is open; null when it is not.
     */
    public BigDecimal settleAmount(BigDecimal paid, BigDecimal taxRate, LocalDate on) {
        BigDecimal due = amountDue(paid, taxRate);
        if (!isEarlyWindowOpen(on, taxRate) || due.signum() <= 0) {
            return null;
        }
        return due.subtract(earlyDiscount(taxRate)).max(zero());
    }

    /** The early discount the invoice was settled with, to the cent; 0 when none was taken. */
    public BigDecimal getEarlyDiscountTaken() {
        return (earlyDiscount == null ? BigDecimal.ZERO : earlyDiscount).setScale(scale(), RoundingMode.HALF_UP);
    }

    /** Settles the invoice with the early discount (0 to clear it again). */
    public void takeEarlyDiscount(BigDecimal amount) {
        this.earlyDiscount = amount;
    }

    /** The cash rounding the invoice was settled with, signed; 0 when it was not settled in cash. */
    public BigDecimal getCashRounding() {
        return (cashRounding == null ? BigDecimal.ZERO : cashRounding).setScale(scale(), RoundingMode.HALF_UP);
    }

    /** Settles the invoice in cash with the given rounding (signed). */
    public void roundForCash(BigDecimal rounding) {
        this.cashRounding = getCashRounding().add(rounding);
    }

    /** What is due once the given amount has been paid, in cash: rounded down to the coin below. */
    public BigDecimal cashDue(BigDecimal paid, BigDecimal taxRate) {
        return currency().toCash(amountDue(paid, taxRate));
    }

    /**
     * The cash rounding shown: the one the invoice was settled with in cash, or else the difference
     * paying what is due in cash would make (signed).
     */
    public BigDecimal cashRounding(BigDecimal paid, BigDecimal taxRate) {
        if (getCashRounding().signum() != 0) {
            return getCashRounding();
        }
        return cashDue(paid, taxRate).subtract(amountDue(paid, taxRate));
    }

    /** The interest dropped when the plan was paid off early, to the cent; 0 when it was not. */
    public BigDecimal getInterestSaved() {
        return (interestSaved == null ? BigDecimal.ZERO : interestSaved).setScale(scale(), RoundingMode.HALF_UP);
    }

    /** Settles the plan early, dropping the given interest (0 to clear it again). */
    public void saveInterest(BigDecimal amount) {
        this.interestSaved = amount;
    }

    /**
     * What would pay off a sent plan not yet paid off on the given day, once the given amount has been
     * paid, at the tax rate: the principal still owed on all its instalments, plus what is unpaid of
     * the interest of the instalments already due (due on or before the day) and any late interest.
     * Within an instalment what is paid goes to its interest first. Null without a plan, or when
     * nothing is due.
     */
    public BigDecimal payoff(BigDecimal paid, BigDecimal taxRate, LocalDate on) {
        if (!hasPlan() || status != InvoiceStatus.SENT || isCreditNote() || getInterestSaved().signum() > 0
                || amountDue(paid, taxRate).signum() <= 0) {
            return null;
        }
        BigDecimal payoff = zero();
        for (Instalment.Progress p : instalmentProgress(paid, taxRate, on)) {
            LocalDate due = p.instalment().dueDate();
            BigDecimal unpaid = due == null || !due.isAfter(on) ? p.unpaid() : p.unpaid().min(p.instalment().principal());
            payoff = payoff.add(unpaid).add(p.chargesUnpaid());
        }
        return payoff;
    }

    /** The number of the instalment the client skipped; null when none was. */
    public Integer getSkippedInstalment() {
        return skippedInstalment;
    }

    /** Skips the instalment with the given number: it pays only its interest (see {@link #schedule}). */
    public void skipInstalment(int number) {
        this.skippedInstalment = number;
    }

    /** Whether the invoice is spread over monthly instalments. */
    public boolean hasPlan() {
        return instalments != null;
    }

    /** How many monthly instalments the invoice is spread over; null when it has no plan. */
    public Integer getInstalments() {
        return instalments;
    }

    /** The plan's annual interest rate, a percentage; 0 when it has no plan. */
    public BigDecimal getInstalmentRate() {
        return instalmentRate;
    }

    /** The plan's down payment, a percentage of the grand total; 0 when there is none. */
    public BigDecimal getPlanDownPct() {
        return planDownPct;
    }

    /** The plan fee charged when the plan was set up, in the invoice's currency; 0 when there is none. */
    public BigDecimal getPlanFee() {
        return planFee.setScale(scale(), RoundingMode.HALF_UP);
    }

    /**
     * Spreads the invoice over the number of monthly instalments at the annual rate, after a down
     * payment of the percentage of the grand total; all have already been checked. Charges the
     * workspace's plan fee: its US dollars converted at the rate of the pricing date (see {@link #pricingDate}), rounded down to the step.
     */
    public void setPlan(int instalments, BigDecimal instalmentRate, BigDecimal planDownPct) {
        this.instalments = instalments;
        this.instalmentRate = instalmentRate;
        this.skippedInstalment = null;
        this.planDownPct = planDownPct;
        this.planFee = planFeeUsd == null ? zero()
                : currency().divideDownToStep(new BigDecimal(planFeeUsd.trim()), usdPerOn(pricingDate()));
    }

    /**
     * Whether the plan's monthly instalment (its first after any down payment) at the tax rate is worth
     * less than {@link #MIN_INSTALMENT_USD} US dollars at the rate on the date; false without a plan.
     */
    public boolean isInstalmentTooSmall(BigDecimal taxRate, LocalDate on) {
        return schedule(taxRate).stream().filter(i -> i.number() >= 1).findFirst()
                .map(i -> i.payment().multiply(usdPerOn(on)).compareTo(MIN_INSTALMENT_USD) < 0)
                .orElse(false);
    }

    /** The plan's down payment at the tax rate: its percentage of the grand total, rounded down to the cent. */
    private BigDecimal downPayment(BigDecimal taxRate) {
        return owedTotal(taxRate).multiply(planDownPct).divide(BigDecimal.valueOf(100), scale(), RoundingMode.FLOOR);
    }

    /**
     * The plan's schedule at the tax rate, an ordinary loan of the grand total; empty when there is no
     * plan. With the monthly rate i (the annual rate / 12 / 100) the instalment is P x i / (1 - (1 +
     * i)^-n), or P / n at 0%, rounded up to the next whole unit of the currency. Each month's interest is the balance still
     * owed x i, rounded half up to the cent, and its principal the instalment less the interest; the
     * last instalment (or any earlier one the instalment would overpay) pays off the whole balance left
     * plus its interest, so the schedule ends at zero. A plan with a down payment starts with it as
     * instalment 0, due on the issue date with no interest, and the instalments are worked out on what
     * is left. The plan fee is added to what is spread over the instalments (after any down payment), so
     * earns their interest. Instalment k falls due k months after the issue date, on the same day (the month's last day when
     * it is shorter). Each credit note issued against it, in the order they were issued, then re-works
     * the instalments due after its issue date by the same rule: from the principal still owed before
     * them less what the credit note took off, over as many instalments, keeping their due dates; the
     * instalments due before then stay as they were. A skipped instalment (never the last) pays only its
     * interest, leaving the balance as it was, and the instalments after it are worked out again by the
     * same rule from that balance over the instalments left, keeping their due dates.
     */
    public List<Instalment> schedule(BigDecimal taxRate) {
        if (!hasPlan()) {
            return List.of();
        }
        List<LocalDate> dues = new ArrayList<>(instalments + 1);
        List<Instalment> schedule = new ArrayList<>();
        BigDecimal principal = owedTotal(taxRate).setScale(scale(), RoundingMode.HALF_UP);
        if (planDownPct.signum() > 0) {
            BigDecimal down = downPayment(taxRate);
            principal = principal.subtract(down);
            dues.add(issueDate);
            schedule.add(new Instalment(0, issueDate, down, zero(), down, principal));
        }
        principal = principal.add(getPlanFee());
        int downs = dues.size();
        for (int k = 1; k <= instalments; k++) {
            dues.add(issueDate == null ? null : issueDate.plusMonths(k));
        }
        schedule.addAll(amortiseSkipping(principal, dues.subList(downs, dues.size()), 1));
        if (creditsIssued != null && !creditsIssued.isBlank()) {
            for (String credit : creditsIssued.split(",")) {
                String[] parts = credit.split("=");
                LocalDate on = LocalDate.parse(parts[0].trim());
                int kept = Math.max(keptBefore(schedule, on), downs);
                if (kept == schedule.size()) {
                    continue;
                }
                BigDecimal left = principalBefore(schedule, kept, taxRate)
                        .subtract(new BigDecimal(parts[1].trim()));
                List<Instalment> reworked = amortiseSkipping(left, dues.subList(kept, dues.size()),
                        schedule.get(kept).number());
                schedule = new ArrayList<>(schedule.subList(0, kept));
                schedule.addAll(reworked);
            }
        }
        return schedule;
    }

    /** How many instalments of the schedule fall due on or before the day, so stay as they are. */
    private static int keptBefore(List<Instalment> schedule, LocalDate on) {
        int kept = 0;
        while (kept < schedule.size() && schedule.get(kept).dueDate() != null
                && !schedule.get(kept).dueDate().isAfter(on)) {
            kept++;
        }
        return kept;
    }

    /** The principal still owed before the instalment at the index: the balance after the one before it. */
    private BigDecimal principalBefore(List<Instalment> schedule, int index, BigDecimal taxRate) {
        return index == 0 ? owedTotal(taxRate).setScale(scale(), RoundingMode.HALF_UP).add(getPlanFee())
                : schedule.get(index - 1).balance();
    }

    /**
     * The principal still owed on a sent plan before its instalments due after the given day, at the
     * tax rate: what a credit note issued that day can take off at most; zero when none falls due after it.
     */
    public BigDecimal principalToCome(LocalDate on, BigDecimal taxRate) {
        List<Instalment> schedule = schedule(taxRate);
        int kept = Math.max(keptBefore(schedule, on), planDownPct.signum() > 0 ? 1 : 0);
        return kept == schedule.size() ? zero() : principalBefore(schedule, kept, taxRate);
    }

    /**
     * {@link #amortise} the principal, then, when the skipped instalment is among them and not the last,
     * make it pay only its interest and work out the instalments after it again (see {@link #schedule}).
     */
    private List<Instalment> amortiseSkipping(BigDecimal principal, List<LocalDate> dues, int firstNumber) {
        List<Instalment> schedule = amortise(principal, dues, firstNumber);
        int index = skippedInstalment == null ? -1 : skippedInstalment - firstNumber;
        if (index < 0 || index >= schedule.size() - 1) {
            return schedule;
        }
        BigDecimal balance = index == 0 ? principal.setScale(scale(), RoundingMode.HALF_UP)
                : schedule.get(index - 1).balance();
        BigDecimal interest = balance.multiply(planRate().divide(BigDecimal.valueOf(1200), MathContext.DECIMAL128))
                .setScale(scale(), RoundingMode.HALF_UP);
        List<Instalment> skipped = new ArrayList<>(schedule.subList(0, index));
        skipped.add(new Instalment(skippedInstalment, dues.get(index), interest, interest, zero(), balance));
        skipped.addAll(amortise(balance, dues.subList(index + 1, dues.size()), skippedInstalment + 1));
        return skipped;
    }

    /**
     * An ordinary loan of the principal at the plan's rate (see {@link #planRate}) over one instalment per due date, numbered
     * from the given number (see {@link #schedule}).
     */
    private List<Instalment> amortise(BigDecimal principal, List<LocalDate> dues, int firstNumber) {
        MathContext mc = MathContext.DECIMAL128;
        BigDecimal i = planRate().divide(BigDecimal.valueOf(1200), mc);
        int n = dues.size();
        BigDecimal payment;
        if (i.signum() == 0) {
            payment = principal.divide(BigDecimal.valueOf(n), mc);
        } else {
            BigDecimal growth = BigDecimal.ONE.add(i).pow(n, mc);
            payment = principal.multiply(i).multiply(growth).divide(growth.subtract(BigDecimal.ONE), mc);
        }
        payment = payment.setScale(0, RoundingMode.CEILING).setScale(scale());
        List<Instalment> schedule = new ArrayList<>(n);
        BigDecimal balance = principal.setScale(scale(), RoundingMode.HALF_UP);
        for (int k = 1; k <= n; k++) {
            BigDecimal interest = balance.multiply(i).setScale(scale(), RoundingMode.HALF_UP);
            BigDecimal owed = balance.add(interest);
            BigDecimal paid = k == n || payment.compareTo(owed) > 0 ? owed : payment;
            BigDecimal repaid = paid.subtract(interest);
            balance = balance.subtract(repaid);
            schedule.add(new Instalment(firstNumber + k - 1, dues.get(k - 1), paid, interest, repaid, balance));
        }
        return schedule;
    }

    /** The plan total at the tax rate: the sum of the instalments; null when there is no plan. */
    public BigDecimal planTotal(BigDecimal taxRate) {
        if (!hasPlan()) {
            return null;
        }
        return schedule(taxRate).stream().map(Instalment::payment).reduce(zero(), BigDecimal::add);
    }

    /**
     * Each instalment of the plan at the tax rate once the given amount has been paid, on the given
     * day: payments (with anything written off) pay the instalments in
     * order, each itself and then its late interest, each paid off before the next. An instalment not
     * paid off more than {@link #graceDays()} days after its due date is overdue; empty when there is no
     * plan.
     */
    public List<Instalment.Progress> instalmentProgress(BigDecimal paid, BigDecimal taxRate, LocalDate today) {
        List<Instalment.Progress> progress = new ArrayList<>();
        BigDecimal left = paid.add(writtenOff(paid, taxRate)).add(getInterestSaved());
        List<Instalment> schedule = schedule(taxRate);
        for (int k = 0; k < schedule.size(); k++) {
            Instalment instalment = schedule.get(k);
            BigDecimal part = left.min(instalment.payment()).max(BigDecimal.ZERO).setScale(scale(), RoundingMode.HALF_UP);
            left = left.subtract(part);
            BigDecimal unpaid = instalment.payment().subtract(part);
            BigDecimal interest = k < instalmentInterest.size() ? instalmentInterest.get(k) : zero();
            BigDecimal toInterest = left.min(interest).max(BigDecimal.ZERO).setScale(scale(), RoundingMode.HALF_UP);
            left = left.subtract(toInterest);
            BigDecimal interestUnpaid = interest.subtract(toInterest);
            Instalment.Status status;
            if (unpaid.signum() <= 0 && interestUnpaid.signum() <= 0) {
                status = Instalment.Status.PAID;
            } else if (instalment.dueDate() != null && today.isAfter(instalment.dueDate().plusDays(graceDays()))) {
                status = Instalment.Status.OVERDUE;
            } else if (part.signum() > 0) {
                status = Instalment.Status.PART_PAID;
            } else {
                status = Instalment.Status.DUE;
            }
            progress.add(new Instalment.Progress(instalment, part, unpaid, interest,
                    interestUnpaid, status));
        }
        return progress;
    }

    /**
     * What is overdue at the tax rate once the given amount has been paid, on the given day: for a plan,
     * only what is unpaid on its overdue instalments, their late interest included; billed by milestones,
     * only what is unpaid on its overdue milestones, their late interest included; otherwise all that is due when it is overdue.
     */
    public BigDecimal overdueAmount(BigDecimal paid, BigDecimal taxRate, LocalDate today) {
        if (statusWhenPaid(paid, taxRate, today) != InvoiceStatus.OVERDUE) {
            return zero();
        }
        if (isBilledByMilestones()) {
            return milestoneProgress(paid, taxRate, today).stream()
                    .filter(p -> p.status() == Milestone.Status.OVERDUE).map(Milestone.Progress::owed)
                    .reduce(zero(), BigDecimal::add);
        }
        if (!hasPlan()) {
            return amountDue(paid, taxRate).subtract(disputedAmount(paid, taxRate));
        }
        return instalmentProgress(paid, taxRate, today).stream()
                .filter(p -> p.status() == Instalment.Status.OVERDUE).map(Instalment.Progress::owed)
                .reduce(zero(), BigDecimal::add);
    }

    /**
     * The due date as listed once the given amount has been paid: for a plan, the due date of its
     * next unpaid instalment (its own due date once all are paid); billed by milestones, the due date of
     * the next reached milestone (in due-date order) with something owing (its own due date when none has);
     * otherwise its own due date.
     */
    public LocalDate listedDueDate(BigDecimal paid, BigDecimal taxRate) {
        if (isBilledByMilestones()) {
            return milestoneProgress(paid, taxRate, LocalDate.MIN).stream()
                    .filter(p -> p.dueDate() != null && p.owed().signum() > 0)
                    .sorted(Comparator.comparing(Milestone.Progress::dueDate)
                            .thenComparing(p -> p.milestone().getId()))
                    .map(Milestone.Progress::dueDate).findFirst().orElse(dueDate);
        }
        if (!hasPlan() || status != InvoiceStatus.SENT) {
            return dueDate;
        }
        return instalmentProgress(paid, taxRate, LocalDate.MIN).stream()
                .filter(p -> p.owed().signum() > 0).map(p -> p.instalment().dueDate())
                .findFirst().orElse(dueDate);
    }

    /**
     * What is still unpaid on the earliest instalment not yet paid off, its late interest included, at
     * the tax rate, once the given amount has been paid; zero when there is no plan or all are paid.
     */
    public BigDecimal earliestUnpaid(BigDecimal paid, BigDecimal taxRate) {
        return instalmentProgress(paid, taxRate, LocalDate.MIN).stream()
                .map(Instalment.Progress::owed).filter(u -> u.signum() > 0)
                .findFirst().orElse(zero());
    }

    public List<Milestone> getMilestones() {
        return milestones;
    }

    /** Adds a milestone to the end of the invoice. */
    public void addMilestone(Milestone milestone) {
        milestones.add(milestone);
    }

    /** The milestone with the id; null when the invoice has none. */
    public Milestone milestone(Long milestoneId) {
        return milestones.stream().filter(m -> m.getId().equals(milestoneId)).findFirst().orElse(null);
    }

    /** What the milestones' percentages add up to; 0 when there are none. */
    public BigDecimal milestonesPct() {
        return milestones.stream().map(Milestone::getPct).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Whether the invoice is billed by milestones once sent: only its reached milestones are then owed. */
    public boolean isBilledByMilestones() {
        return !milestones.isEmpty() && status == InvoiceStatus.SENT;
    }

    /** Whether the sent invoice has milestones still to be reached. */
    public boolean hasPendingMilestones() {
        return isBilledByMilestones() && milestones.stream().anyMatch(m -> !m.isReached());
    }

    /**
     * Each milestone's amount at the tax rate, in the order of the milestones: its percentage of what the
     * client pays ({@link #owedTotal}, the grand total less the withholding), rounded down to the cent, with
     * the cents left over (of the milestones' share of it, rounded half up) going one each to the largest
     * remainders, the lower id first on a tie, so they add up exactly.
     */
    public List<BigDecimal> milestoneAmounts(BigDecimal taxRate) {
        int scale = scale();
        BigDecimal units = owedTotal(taxRate).movePointRight(scale);
        BigDecimal hundred = BigDecimal.valueOf(100);
        int n = milestones.size();
        BigInteger[] floors = new BigInteger[n];
        BigDecimal[] remainders = new BigDecimal[n];
        BigInteger sum = BigInteger.ZERO;
        for (int k = 0; k < n; k++) {
            BigDecimal exact = units.multiply(milestones.get(k).getPct()).divide(hundred);
            floors[k] = exact.setScale(0, RoundingMode.FLOOR).toBigInteger();
            remainders[k] = exact.subtract(new BigDecimal(floors[k]));
            sum = sum.add(floors[k]);
        }
        BigInteger target = units.multiply(milestonesPct()).divide(hundred).setScale(0, RoundingMode.HALF_UP)
                .toBigInteger();
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            order.add(k);
        }
        order.sort(Comparator.comparing((Integer k) -> remainders[k]).reversed()
                .thenComparing(k -> milestones.get(k).getId(), Comparator.nullsLast(Comparator.naturalOrder())));
        int left = target.subtract(sum).intValue();
        for (int k = 0; k < left && k < n; k++) {
            floors[order.get(k)] = floors[order.get(k)].add(BigInteger.ONE);
        }
        List<BigDecimal> amounts = new ArrayList<>(n);
        for (BigInteger f : floors) {
            amounts.add(new BigDecimal(f).movePointLeft(scale).setScale(scale));
        }
        return amounts;
    }

    /** What the reached milestones add up to at the tax rate. */
    public BigDecimal reachedTotal(BigDecimal taxRate) {
        List<BigDecimal> amounts = milestoneAmounts(taxRate);
        BigDecimal sum = zero();
        for (int k = 0; k < milestones.size(); k++) {
            if (milestones.get(k).isReached()) {
                sum = sum.add(amounts.get(k));
            }
        }
        return sum;
    }

    /** The standard days after it is reached a milestone falls due when the client has no payment terms. */
    public static final int MILESTONE_TERMS_DAYS = 14;

    /**
     * The day a reached milestone falls due: the client's payment terms (their own, else their region's)
     * after the day it was reached, or {@link #MILESTONE_TERMS_DAYS} days when they have none; null while
     * it is pending.
     */
    public LocalDate milestoneDueDate(Milestone milestone) {
        if (!milestone.isReached()) {
            return null;
        }
        return milestone.getReachedOn().plusDays(clientTermsDays == null ? MILESTONE_TERMS_DAYS : clientTermsDays);
    }

    /** The indexes of the reached milestones, in the order they are paid: by due date, then id. */
    private List<Integer> reachedInDueOrder() {
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < milestones.size(); k++) {
            if (milestones.get(k).isReached()) {
                order.add(k);
            }
        }
        order.sort(Comparator.comparing((Integer k) -> milestoneDueDate(milestones.get(k)))
                .thenComparing(k -> milestones.get(k).getId(), Comparator.nullsLast(Comparator.naturalOrder())));
        return order;
    }

    /**
     * Each milestone with its amount at the tax rate, its due date (see {@link #milestoneDueDate}), what is
     * paid off it and of its late interest once the given amount has been paid, and its status on the day: a
     * milestone not reached (or on a draft) is pending; payments (with anything written off and what credit
     * notes took off) pay the reached milestones in due-date order (then id), each milestone's amount and
     * then its late interest, each paid off before the next; a reached one not paid off more than
     * {@link #graceDays()} days after its own due date is overdue. In the order of the milestones.
     */
    public List<Milestone.Progress> milestoneProgress(BigDecimal paid, BigDecimal taxRate, LocalDate today) {
        List<BigDecimal> amounts = milestoneAmounts(taxRate);
        BigDecimal left = paid.add(writtenOff(paid, taxRate)).add(getCredited());
        Milestone.Progress[] progress = new Milestone.Progress[milestones.size()];
        for (int k = 0; k < milestones.size(); k++) {
            Milestone m = milestones.get(k);
            if (status != InvoiceStatus.SENT || !m.isReached()) {
                progress[k] = new Milestone.Progress(m, amounts.get(k), zero(), null, zero(), zero(),
                        Milestone.Status.PENDING);
            }
        }
        if (status == InvoiceStatus.SENT) {
            for (int k : reachedInDueOrder()) {
                Milestone m = milestones.get(k);
                BigDecimal amount = amounts.get(k);
                LocalDate due = milestoneDueDate(m);
                BigDecimal part = left.min(amount).max(BigDecimal.ZERO).setScale(scale(), RoundingMode.HALF_UP);
                left = left.subtract(part);
                BigDecimal interest = milestoneInterest.getOrDefault(m.getId(), zero());
                BigDecimal toInterest = left.min(interest).max(BigDecimal.ZERO).setScale(scale(), RoundingMode.HALF_UP);
                left = left.subtract(toInterest);
                BigDecimal interestUnpaid = interest.subtract(toInterest);
                Milestone.Status st;
                if (part.compareTo(amount) >= 0 && interestUnpaid.signum() <= 0) {
                    st = Milestone.Status.PAID;
                } else if (today.isAfter(due.plusDays(graceDays()))) {
                    st = Milestone.Status.OVERDUE;
                } else if (part.signum() > 0) {
                    st = Milestone.Status.PART_PAID;
                } else {
                    st = Milestone.Status.DUE;
                }
                progress[k] = new Milestone.Progress(m, amount, part, due, interest, interestUnpaid, st);
            }
        }
        return List.of(progress);
    }

    public List<InvoiceLine> getLines() {
        return lines;
    }

    /** Whether lines can still be added: only a draft is still being written. */
    public boolean isDraft() {
        return status == InvoiceStatus.DRAFT;
    }

    /** Whether the invoice has been voided: it no longer counts, takes no payments and cannot be changed. */
    public boolean isVoid() {
        return status == InvoiceStatus.VOID;
    }

    /** Voids a sent invoice. */
    public void markVoid() {
        this.status = InvoiceStatus.VOID;
    }

    /** Whether the due date was set by hand; it is then kept when the invoice is sent. */
    public String getSupplyRegion() {
        return supplyRegion;
    }

    /** The client's purchase order number for the invoice; null when none was given. */
    public String getPoNumber() {
        return poNumber;
    }

    /** Sets the client's purchase order number; blank for none. */
    public void setPoNumber(String poNumber) {
        this.poNumber = poNumber == null || poNumber.isBlank() ? null : poNumber.trim();
    }

    /** Sets the place of supply; blank for none, so the client's region applies. */
    public void setSupplyRegion(String supplyRegion) {
        this.supplyRegion = supplyRegion == null || supplyRegion.isBlank() ? null : supplyRegion.trim().toUpperCase();
    }

    public boolean isManualDueDate() {
        return manualDueDate;
    }

    /**
     * Sets the due date by hand: not before the issue date nor more than {@link #MAX_DUE_DAYS} days
     * after it, or it is refused.
     */
    public void setDueDate(LocalDate dueDate) {
        if (issueDate != null && dueDate.isBefore(issueDate)) {
            throw new InvoiceRejectedException(
                    "The due date cannot be before the issue date (" + issueDate + ").");
        }
        if (issueDate != null && dueDate.isAfter(issueDate.plusDays(MAX_DUE_DAYS))) {
            throw new InvoiceRejectedException("The due date can be at most " + MAX_DUE_DAYS
                    + " days after the issue date, so no later than " + issueDate.plusDays(MAX_DUE_DAYS) + ".");
        }
        this.dueDate = dueDate;
        this.manualDueDate = true;
    }

    /**
     * Marks the invoice sent, issued on the given date and due on the given date, unless its due date
     * was set by hand, which is kept.
     */
    public void markSent(LocalDate issueDate, LocalDate dueDate) {
        this.status = InvoiceStatus.SENT;
        lines.forEach(InvoiceLine::fixPrice);
        this.issueDate = issueDate;
        if (!manualDueDate) {
            this.dueDate = dueDate;
        }
    }

    /** Whether a due date set by hand is already past on the given send date. */
    public boolean dueDatePassedBy(LocalDate sendDate) {
        return manualDueDate && dueDate != null && dueDate.isBefore(sendDate);
    }

    /** Puts a sent invoice back to draft, so it has to be sent again. */
    public void reopen() {
        this.status = InvoiceStatus.DRAFT;
    }

    /** The standard days past its due date an invoice is given before it becomes overdue: a week's grace. */
    public static final int GRACE_DAYS = 7;

    /**
     * The annual late-interest rate this invoice is charged: its client's own, else the workspace's,
     * but no more than the legal maximum of the client's region.
     */
    public BigDecimal lateInterestRate(BigDecimal workspaceRate) {
        BigDecimal rate = clientInterestRate == null ? workspaceRate : clientInterestRate;
        if (regionMaxInterest == null || rate == null) {
            return rate;
        }
        return rate.min(new BigDecimal(regionMaxInterest.trim()));
    }

    /**
     * Days past its due date this invoice is given before it becomes overdue: its client's grace
     * period, else their region's, else the standard week.
     */
    public int graceDays() {
        return clientGraceDays == null ? GRACE_DAYS : clientGraceDays;
    }

    /**
     * The status on the given day once the given amount has been paid against the grand total at the
     * tax rate: a sent invoice with something still due more than {@link #graceDays()} days after its
     * due date is overdue, part paid or not. An invoice spread over instalments is instead overdue when any
     * of its instalments is (see {@link #instalmentProgress}), and one billed by milestones when any of its
     * milestones is (see {@link #milestoneProgress}).
     */
    public InvoiceStatus statusWhenPaid(BigDecimal paid, BigDecimal taxRate, LocalDate today) {
        return statusWhenPaid(paid, null, taxRate, today);
    }

    /**
     * As {@link #statusWhenPaid(BigDecimal, BigDecimal, LocalDate)}, but a fully paid invoice whose
     * paid date (null when not known) is after its due date is paid late.
     */
    public InvoiceStatus statusWhenPaid(BigDecimal paid, LocalDate paidOn, BigDecimal taxRate, LocalDate today) {
        if (status != InvoiceStatus.SENT) {
            return status;
        }
        if (isWrittenOff()) {
            return InvoiceStatus.WRITTEN_OFF;
        }
        BigDecimal amountDue = amountDue(paid, taxRate);
        boolean due = amountDue.signum() > 0;
        BigDecimal disputed = disputedAmount(paid, taxRate);
        if (isWhollyDisputed() || (due && disputed.compareTo(amountDue) >= 0)) {
            return InvoiceStatus.DISPUTED;
        }
        boolean restDue = amountDue.subtract(disputed).signum() > 0;
        if (hasPlan()) {
            if (due && instalmentProgress(paid, taxRate, today).stream()
                    .anyMatch(p -> p.status() == Instalment.Status.OVERDUE)) {
                return InvoiceStatus.OVERDUE;
            }
        } else if (isBilledByMilestones()) {
            if (restDue && milestoneProgress(paid, taxRate, today).stream()
                    .anyMatch(p -> p.status() == Milestone.Status.OVERDUE)) {
                return InvoiceStatus.OVERDUE;
            }
        } else if (restDue && dueDate != null && today.isAfter(dueDate.plusDays(graceDays()))) {
            return InvoiceStatus.OVERDUE;
        }
        if (!due && hasPendingMilestones()) {
            return InvoiceStatus.AWAITING_MILESTONE;
        }
        if (paid.signum() <= 0) {
            return !due && getCredited().signum() > 0 ? InvoiceStatus.CREDITED : status;
        }
        if (due) {
            return InvoiceStatus.PART_PAID;
        }
        List<Instalment> schedule = schedule(taxRate);
        LocalDate finalDue = hasPlan() ? schedule.get(schedule.size() - 1).dueDate()
                : isBilledByMilestones() ? milestones.stream().map(this::milestoneDueDate).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(dueDate)
                : dueDate;
        return paidOn != null && finalDue != null && paidOn.isAfter(finalDue) ? InvoiceStatus.PAID_LATE
                : InvoiceStatus.PAID;
    }

    /**
     * The date the invoice was fully paid: the date of the payment that cleared its grand total at the
     * tax rate, taking the parts of payments (not reversed) in the order they were applied (credit used
     * counts on the day it was applied); null while something is still due.
     */
    public LocalDate paidOn(List<Payment> payments, BigDecimal taxRate) {
        BigDecimal total = payable(taxRate);
        if (status != InvoiceStatus.SENT || isWrittenOff() || total.signum() <= 0 || hasPendingMilestones()) {
            return null;
        }
        record Part(LocalDate on, Long paymentId, BigDecimal amount) {
        }
        List<Part> inOrder = payments.stream().filter(p -> !p.isReversed())
                .flatMap(p -> p.getAllocations().stream().filter(a -> a.getInvoiceId().equals(id))
                        .map(a -> new Part(a.getDate() == null ? p.getDate() : a.getDate(), p.getId(), a.getAmount())))
                .sorted(Comparator.comparing(Part::on).thenComparing(Part::paymentId)).toList();
        BigDecimal sum = BigDecimal.ZERO;
        for (Part p : inOrder) {
            sum = sum.add(p.amount());
            if (sum.signum() > 0 && isSmallEnoughToWriteOff(total.subtract(sum), p.on(), taxRate)) {
                return p.on();
            }
        }
        return null;
    }

    /**
     * What is still due once the given amount has been paid: the grand total less it, less what credit
     * notes have credited, less anything written off — so nothing once
     * payments leave less than {@link #WRITE_OFF_LIMIT} that is no more than {@link #WRITE_OFF_SHARE}
     * of the grand total. A credit note itself, and a written-off
     * invoice, have nothing due.
     */
    public BigDecimal amountDue(BigDecimal paid, BigDecimal taxRate) {
        if (isCreditNote() || isWrittenOff()) {
            return zero();
        }
        return payable(taxRate).subtract(paid).subtract(writtenOff(paid, taxRate));
    }

    /**
     * What is written off once the given amount has been paid: when payments leave less than
     * {@link #WRITE_OFF_LIMIT} still due that is no more than {@link #WRITE_OFF_SHARE} of the grand
     * total, all of what is left; otherwise nothing. Once the invoice is
     * written off as a bad debt, everything it still owed then, late charges included.
     */
    public BigDecimal writtenOff(BigDecimal paid, BigDecimal taxRate) {
        if (isWrittenOff() && !isCreditNote()) {
            return payable(taxRate).subtract(paid).max(BigDecimal.ZERO)
                    .setScale(scale(), RoundingMode.HALF_UP);
        }
        BigDecimal left = payable(taxRate).subtract(paid);
        return !isCreditNote() && paid.signum() > 0 && left.signum() > 0 && isSmallEnoughToWriteOff(left, taxRate)
                ? left.setScale(scale(), RoundingMode.HALF_UP)
                : zero();
    }

    /**
     * The least a part payment may be at the tax rate once the amount was already paid:
     * {@link #MINIMUM_PART_SHARE} of what the invoice still has due.
     */
    public BigDecimal minimumPart(BigDecimal paid, BigDecimal taxRate) {
        return amountDue(paid, taxRate).max(BigDecimal.ZERO).multiply(MINIMUM_PART_SHARE)
                .setScale(scale(), RoundingMode.HALF_UP);
    }

    /**
     * Whether paying the amount on top of what was already paid is too small a part payment: under
     * {@link #minimumPart} while leaving something still due (what would be written off counts as settled).
     * For a plan the least is instead what is still unpaid on its earliest unpaid instalment.
     */
    public boolean isTooSmallPart(BigDecimal amount, BigDecimal paid, BigDecimal taxRate) {
        if (hasPlan()) {
            return amount.compareTo(earliestUnpaid(paid, taxRate)) < 0
                    && amountDue(paid.add(amount), taxRate).signum() > 0;
        }
        return amount.compareTo(minimumPart(paid, taxRate)) < 0 && amountDue(paid.add(amount), taxRate).signum() > 0;
    }

    /** Adds a line to the end of the invoice. */
    public void addLine(InvoiceLine line) {
        line.placeOn(this);
        lines.add(line);
    }

    /**
     * How many units of the given tiered line's unit type the invoice's earlier lines take through the
     * bands before it: the tiered lines of that unit type before it in line order (lower id first, lines
     * not yet saved last in the order they were added).
     */
    BigDecimal tieredUnitsBefore(InvoiceLine line) {
        List<InvoiceLine> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparing(InvoiceLine::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        BigDecimal before = monthUnitsBefore(line.getUnitType());
        for (InvoiceLine other : ordered) {
            if (other == line) {
                return before;
            }
            if (other.isTiered() && Objects.equals(other.getUnitType(), line.getUnitType())) {
                before = before.add(other.billedQty());
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * How many units of the line it credits the credit note's earlier lines (in line order) already give
     * back, so the given line carries on down the bands from below them.
     */
    BigDecimal creditedUnitsBefore(InvoiceLine line) {
        List<InvoiceLine> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparing(InvoiceLine::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        BigDecimal before = BigDecimal.ZERO;
        for (InvoiceLine other : ordered) {
            if (other == line) {
                return before;
            }
            if (other.isTiered() && Objects.equals(other.getCreditOfLine(), line.getCreditOfLine())) {
                before = before.add(other.billedQty());
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * How many units of the tiered unit type the client was already billed this calendar month before this
     * invoice: the tiered lines of that unit type on the client's other sent invoices (not credit notes)
     * issued earlier in the same month as this one is priced in (see {@link #pricingDate}), or on the same date with a lower id. Drafts, void
     * invoices and credit notes don't count; a credit note itself carries on from none.
     */
    private BigDecimal monthUnitsBefore(String unitType) {
        BigDecimal before = BigDecimal.ZERO;
        LocalDate on = pricingDate();
        if (clientTieredUnits == null || unitType == null || on == null || isCreditNote()) {
            return before;
        }
        for (String entry : clientTieredUnits.split(String.valueOf((char) 30))) {
            String[] parts = entry.split(String.valueOf((char) 31), -1);
            if (parts.length < 4 || !unitType.equals(parts[2])) {
                continue;
            }
            Long otherId = Long.valueOf(parts[0]);
            LocalDate issued = LocalDate.parse(parts[1]);
            boolean sameMonth = issued.getYear() == on.getYear() && issued.getMonth() == on.getMonth();
            boolean earlier = issued.isBefore(on)
                    || (issued.isEqual(on) && (id == null || otherId < id));
            if (sameMonth && earlier) {
                before = before.add(new BigDecimal(parts[3]));
            }
        }
        return before;
    }

    /** Whether the invoice has the line with the given id. */
    public boolean hasLine(Long lineId) {
        return lines.stream().anyMatch(l -> lineId.equals(l.getId()));
    }

    /**
     * Removes the line with the given id; whether the invoice had it. Refused when what is left would
     * be less than a fixed discount on the invoice.
     */
    public boolean removeLine(Long lineId) {
        InvoiceLine line = lines.stream().filter(l -> lineId.equals(l.getId())).findFirst().orElse(null);
        if (line == null) {
            return false;
        }
        int index = lines.indexOf(line);
        lines.remove(line);
        // Removing a tiered line reprices the others of its unit type, so what is left is worked out without it.
        BigDecimal left = discountableSubtotal();
        if (discountAmount.signum() > 0 && left.compareTo(discountAmount) < 0) {
            lines.add(index, line);
            throw new InvoiceRejectedException("This line cannot be removed: the "
                    + currency().format(left) + " left would be less than the "
                    + currency().format(discountAmount) + " discount.");
        }
        return true;
    }

    /**
     * Changes the line with the given id to the given values; whether the invoice had it. Refused when
     * the subtotal would then be less than a fixed discount on the invoice.
     */
    public boolean updateLine(Long lineId, String description, BigDecimal qty, String unitType, BigDecimal unitPrice,
            String tiers, BigDecimal increment, BigDecimal minUnits, boolean overtime, boolean taxExempt, BigDecimal discountPct,
            BigDecimal taxRate, LocalDate periodStart, LocalDate periodEnd, BigDecimal percentComplete) {
        InvoiceLine line = lines.stream().filter(l -> lineId.equals(l.getId())).findFirst().orElse(null);
        if (line == null) {
            return false;
        }
        InvoiceLine was = line.copy();
        line.update(description, qty, unitType, unitPrice, tiers, increment, minUnits, overtime, taxExempt, discountPct, taxRate);
        line.setPeriod(periodStart, periodEnd);
        line.setPercentComplete(percentComplete);
        // Changing a tiered line reprices the others of its unit type, so the subtotal is worked out with it changed.
        BigDecimal left = discountableSubtotal();
        if (discountAmount.signum() > 0 && left.compareTo(discountAmount) < 0) {
            line.update(was.getDescription(), was.getQty(), was.getUnitType(), was.getUnitPrice(), was.tiers(), was.getIncrement(), was.getMinUnits(), was.isOvertime(),
                    was.isTaxExempt(), was.getDiscountPct(), was.getTaxRate());
            line.setPeriod(was.getPeriodStart(), was.getPeriodEnd());
            line.setPercentComplete(was.getPercentComplete());
            throw new InvoiceRejectedException("This line cannot be changed: the "
                    + currency().format(left) + " subtotal would be less than the "
                    + currency().format(discountAmount) + " discount.");
        }
        return true;
    }

    /**
     * The sum of the line amounts, each after its own discount and already to the cent — so the subtotal
     * matches what a client gets by adding up the lines shown.
     */
    public BigDecimal subtotal() {
        return lines.stream().map(l -> l.amount(scale()))
                .reduce(zero(), BigDecimal::add);
    }

    /**
     * The part of the subtotal the invoice discount is worked out on and shared over: the amounts of the lines not
     * priced from the rates (a rate card or the price list), whose prices are already negotiated.
     */
    public BigDecimal discountableSubtotal() {
        return lines.stream().filter(l -> !l.isRatePriced()).map(l -> l.amount(scale()))
                .reduce(zero(), BigDecimal::add);
    }

    /**
     * The amount taken off: the fixed amount when there is one (never more than the discountable subtotal, should
     * lines have been changed since), otherwise the discount percentage of the discountable subtotal (see
     * {@link #discountableSubtotal()}), rounded down to the cent; the volume discount instead when that is more. When there is a cap, it limits everything taken off — the line discounts plus
     * this — so this shrinks to what the line discounts leave under the cap, never below zero.
     */
    public BigDecimal discount() {
        BigDecimal discountable = discountableSubtotal();
        BigDecimal discount = discountAmount.signum() > 0
                ? discountAmount.min(discountable).setScale(scale(), RoundingMode.HALF_UP)
                : discountable.multiply(discountPct).divide(BigDecimal.valueOf(100)).setScale(scale(), RoundingMode.DOWN);
        discount = discount.max(volumeDiscount());
        if (discountCap == null) {
            return discount;
        }
        BigDecimal lineDiscounts = lines.stream().map(l -> l.discount(scale()))
                .reduce(zero(), BigDecimal::add);
        BigDecimal left = discountCap.setScale(scale(), RoundingMode.HALF_UP).subtract(lineDiscounts).max(zero());
        return discount.min(left);
    }

    /**
     * The volume discount: the percentage of the highest of the workspace's volume discount thresholds
     * the subtotal is over (each converted from US dollars at the pricing date's rate (see {@link #pricingDate}) and rounded down
     * to the step) of the discountable subtotal (see {@link #discountableSubtotal()}), rounded down to the cent. Nothing when it passes none, or for a
     * credit note.
     */
    public BigDecimal volumeDiscount() {
        if (volumeDiscounts == null || isCreditNote()) {
            return zero();
        }
        BigDecimal subtotal = subtotal();
        BigDecimal pct = BigDecimal.ZERO;
        BigDecimal highest = null;
        for (String entry : volumeDiscounts.split(",")) {
            String[] parts = entry.split("=");
            BigDecimal over = currency().divideDownToStep(new BigDecimal(parts[0].trim()), usdPerOn(pricingDate()));
            if (subtotal.compareTo(over) > 0 && (highest == null || over.compareTo(highest) >= 0)) {
                highest = over;
                pct = new BigDecimal(parts[1].trim());
            }
        }
        return discountableSubtotal().multiply(pct).movePointLeft(2).setScale(scale(), RoundingMode.DOWN);
    }

    /**
     * Each line's share of the discount, in the order of the lines. The discount's cents are split in
     * proportion to the amounts of the lines not priced from the rates (the others get none): each line first gets the whole cents of its exact share
     * (rounded down), then the cents left over go one each to the lines with the largest leftover
     * fractions; between equal fractions the cent goes to the line with the larger amount, and between
     * equal amounts too to the lower line id. The shares add up to the discount.
     */
    public List<BigDecimal> discountShares() {
        BigInteger discountCents = discount().movePointRight(scale()).toBigIntegerExact();
        BigInteger subtotalCents = discountableSubtotal().movePointRight(scale()).toBigIntegerExact();
        int n = lines.size();
        BigInteger[] shares = new BigInteger[n];
        BigInteger[] leftovers = new BigInteger[n];
        BigInteger given = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            if (subtotalCents.signum() == 0 || lines.get(i).isRatePriced()) {
                shares[i] = BigInteger.ZERO;
                leftovers[i] = BigInteger.ZERO;
            } else {
                // The exact share is discount * amount / subtotal; all fractions share the subtotal
                // as denominator, so the remainders compare as the fractions do.
                BigInteger[] qr = discountCents.multiply(lines.get(i).amount(scale()).movePointRight(scale()).toBigIntegerExact())
                        .divideAndRemainder(subtotalCents);
                shares[i] = qr[0];
                leftovers[i] = qr[1];
            }
            given = given.add(shares[i]);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, BigInteger>comparing(i -> leftovers[i]).reversed()
                .thenComparing(Comparator.<Integer, BigDecimal>comparing(i -> lines.get(i).amount(scale())).reversed())
                .thenComparing(i -> lines.get(i).getId(), Comparator.nullsLast(Comparator.naturalOrder())));
        int left = discountCents.subtract(given).intValueExact();
        for (int k = 0; k < left; k++) {
            int i = order.get(k);
            shares[i] = shares[i].add(BigInteger.ONE);
        }
        List<BigDecimal> result = new ArrayList<>(n);
        for (BigInteger share : shares) {
            result.add(new BigDecimal(share, scale()));
        }
        return result;
    }

    /**
     * Whether the invoice is reverse charged: its client is registered (a valid tax number whose two
     * letters are the region's code) in a region other than the workspace's home region and accounts for the tax itself, so no line is taxed.
     */
    public boolean isReverseCharge() {
        return TaxNumber.isValid(outOfRegionTaxNumber);
    }

    /**
     * The rate lines without their own are taxed at: the client's regional rate in force on the day it is taxed as of
     * (see {@link #taxDate}: today for a draft, a credit note's original's issue date), that is the latest change on or before it, otherwise the
     * region's own rate; or the given workspace rate when the region has neither.
     */
    public BigDecimal clientTaxRate(BigDecimal workspaceRate) {
        BigDecimal rate = regionTaxRate == null ? null : new BigDecimal(regionTaxRate.trim());
        LocalDate on = taxDate();
        if (regionRateChanges != null && on != null) {
            for (String entry : regionRateChanges.split(",")) {
                String[] parts = entry.split("=");
                if (LocalDate.parse(parts[0].trim()).isAfter(on)) {
                    break;
                }
                rate = new BigDecimal(parts[1].trim());
            }
        }
        return rate == null ? workspaceRate : rate;
    }

    /**
     * Each line's exact tax, in the order of the lines: the line's own rate (a percentage), or when it
     * has none its unit type's rate on the client's rate card, or else the client's regional rate, or the given workspace rate when that has none either, on the line's amount less its share of the discount,
     * unrounded. Tax-exempt lines, and every line of a reverse-charged invoice, have no tax. A tax-exempt
     * client is exempt from the general rate only (regional or workspace): its lines with their own rate are still taxed at it.
     * The exact tax of the surcharge and minimum charge at the client's rate follows, as one more line.
     */
    private List<BigDecimal> exactLineTaxes(BigDecimal taxRate) {
        List<BigDecimal> shares = discountShares();
        List<BigDecimal> rates = lineRates(taxRate, true);
        List<BigDecimal> taxes = new ArrayList<>(lines.size() + 1);
        for (int i = 0; i < lines.size(); i++) {
            taxes.add(lines.get(i).amount(scale()).subtract(shares.get(i)).multiply(rates.get(i)).movePointLeft(2));
        }
        taxes.add(surcharge().add(minimumCharge()).multiply(rates.get(lines.size())).movePointLeft(2));
        return taxes;
    }

    /**
     * Whether the client's tax exemption covers the invoice: the client is exempt and the day the invoice is
     * taxed as of (see {@link #taxDate}) is on or before the exemption's end date, when it has one.
     */
    private boolean isClientExempt() {
        if (!clientTaxExempt) {
            return false;
        }
        LocalDate on = taxDate();
        return clientTaxExemptUntil == null || on == null || !on.isAfter(clientTaxExemptUntil);
    }

    /**
     * The rate (a percentage) each line is taxed at, in the order of the lines, then the rate of the
     * surcharge and minimum charge: the line's own rate, otherwise its unit type's rate on the client's rate
     * card, otherwise the client's (zero for a tax-exempt client, whose card rates are not charged either); zero for a tax-exempt line, and on every line of a reverse-charged invoice when the rates
     * are those charged rather than those the tax is taken out of tax-inclusive prices at.
     */
    private List<BigDecimal> lineRates(BigDecimal taxRate, boolean charged) {
        boolean reverseCharged = charged && isReverseCharge();
        boolean exempt = isClientExempt();
        BigDecimal clientRate = exempt ? BigDecimal.ZERO : clientTaxRate(taxRate);
        List<BigDecimal> rates = new ArrayList<>(lines.size() + 1);
        for (InvoiceLine line : lines) {
            BigDecimal cardRate = exempt ? null : cardTaxRate(line.getUnitType());
            rates.add(reverseCharged || line.isTaxExempt() ? BigDecimal.ZERO
                    : line.taxRate(cardRate == null ? clientRate : cardRate));
        }
        rates.add(reverseCharged ? BigDecimal.ZERO : clientRate);
        return rates;
    }

    /**
     * The tax rate of the client's rate card entry for the unit type (matched ignoring case and surrounding
     * spaces) in force on the day the invoice is taxed as of (see {@link #taxDate}) (the latest entry while there is none); null when the unit type has
     * no entry in force or its entry has no tax rate.
     */
    private BigDecimal cardTaxRate(String unitType) {
        if (unitType == null || cardTaxRates == null) {
            return null;
        }
        String rate = null;
        LocalDate latest = null;
        for (String entry : cardTaxRates.split("\u001e")) {
            String[] parts = entry.split("\u001f", -1);
            if (parts.length != 3 || !parts[0].equalsIgnoreCase(unitType.trim())) {
                continue;
            }
            LocalDate from = parts[1].isBlank() ? LocalDate.MIN : LocalDate.parse(parts[1].trim());
            if ((taxDate() == null || !from.isAfter(taxDate())) && (latest == null || from.isAfter(latest))) {
                latest = from;
                rate = parts[2];
            }
        }
        return rate == null || rate.isBlank() ? null : new BigDecimal(rate.trim());
    }

    /**
     * The lines (by index, the surcharge and minimum charge last) grouped by the rate they are taxed at,
     * the lower rate first; the rates charged, or those tax is taken out of tax-inclusive prices at.
     */
    private Map<BigDecimal, List<Integer>> byRate(BigDecimal taxRate, boolean charged) {
        List<BigDecimal> rates = lineRates(taxRate, charged);
        Map<BigDecimal, List<Integer>> groups = new TreeMap<>();
        for (int i = 0; i < rates.size(); i++) {
            groups.computeIfAbsent(rates.get(i), r -> new ArrayList<>()).add(i);
        }
        return groups;
    }

    /**
     * Each line's share of the invoice tax, in the order of the lines. The tax is worked out once for
     * each rate, and each rate's tax is split over the lines at that rate in proportion to their exact
     * taxes as the discount is split: each line first gets the whole cents of its exact share (rounded
     * down), then the cents left over go one each to the lines with the largest leftover fractions;
     * between equal fractions the cent goes to the line with the larger exact tax, and between equal
     * exact taxes too to the lower line id. The line taxes add up to the invoice tax, less the share of
     * the surcharge and minimum charge, which is split as one more line at the client's rate.
     */
    public List<BigDecimal> lineTaxes(BigDecimal taxRate) {
        if (clientPricesIncludeTax) {
            return isReverseCharge() ? lines.stream().map(l -> zero()).toList() : inclusiveLineTaxes(taxRate);
        }
        List<BigDecimal> exact = exactLineTaxes(taxRate);
        BigInteger[] shares = new BigInteger[exact.size()];
        for (List<Integer> group : byRate(taxRate, true).values()) {
            share(exact, group, shares);
        }
        List<BigDecimal> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            result.add(new BigDecimal(shares[i], scale()));
        }
        return result;
    }

    /** Splits the rounded tax of one rate's lines (by index) over them, into the given shares in cents. */
    private void share(List<BigDecimal> allExact, List<Integer> group, BigInteger[] out) {
        int n = group.size();
        List<BigDecimal> exact = new ArrayList<>(n);
        for (int i : group) {
            exact.add(allExact.get(i));
        }
        BigInteger taxCents = roundTax(exact).movePointRight(scale()).toBigIntegerExact();
        int common = exact.stream().mapToInt(BigDecimal::scale).max().orElse(0);
        BigInteger[] weights = new BigInteger[n];
        BigInteger total = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            weights[i] = exact.get(i).setScale(common).unscaledValue();
            total = total.add(weights[i]);
        }
        boolean flipped = total.signum() < 0;
        if (flipped) {
            // Split the magnitude, so the leftover cents are always added, then flip the shares back.
            taxCents = taxCents.negate();
            total = total.negate();
            for (int i = 0; i < n; i++) {
                weights[i] = weights[i].negate();
            }
        }
        BigInteger[] shares = new BigInteger[n];
        BigInteger[] leftovers = new BigInteger[n];
        BigInteger given = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            if (total.signum() == 0) {
                shares[i] = BigInteger.ZERO;
                leftovers[i] = BigInteger.ZERO;
            } else {
                // The exact share is tax * exact line tax / total exact tax; all fractions share the
                // total as denominator, so the remainders compare as the fractions do.
                BigInteger[] qr = taxCents.multiply(weights[i]).divideAndRemainder(total);
                if (qr[1].signum() < 0) {
                    qr[0] = qr[0].subtract(BigInteger.ONE);
                    qr[1] = qr[1].add(total);
                }
                shares[i] = qr[0];
                leftovers[i] = qr[1];
            }
            given = given.add(shares[i]);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, BigInteger>comparing(i -> leftovers[i]).reversed()
                .thenComparing(Comparator.<Integer, BigInteger>comparing(i -> weights[i]).reversed())
                .thenComparing(i -> group.get(i) < lines.size() ? lines.get(group.get(i)).getId() : null,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        int left = taxCents.subtract(given).intValueExact();
        for (int k = 0; k < left; k++) {
            int i = order.get(k);
            shares[i] = shares[i].add(BigInteger.ONE);
        }
        for (int i = 0; i < n; i++) {
            out[group.get(i)] = flipped ? shares[i].negate() : shares[i];
        }
    }

    /**
     * The sum of the exact line taxes, rounded once to the cent: half up, or down when the region the
     * invoice is taxed in requires it.
     */
    private BigDecimal roundTax(List<BigDecimal> exact) {
        return exact.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(scale(), taxRounding());
    }

    /** How a rate's tax is rounded to the cent: down in a region that requires it, otherwise half up. */
    private RoundingMode taxRounding() {
        return regionRoundsDown ? RoundingMode.DOWN : RoundingMode.HALF_UP;
    }

    /**
     * The tax charged at the given rate (a percentage): for each rate, the exact taxes of the lines
     * (and the surcharge and minimum charge) at it added up and rounded half up to the cent (down in a
     * region that requires it); the
     * invoice tax is the total over the rates. It is what the line taxes add up to.
     */
    public BigDecimal tax(BigDecimal taxRate) {
        if (clientPricesIncludeTax) {
            return isReverseCharge() ? zero() : inclusiveTax(taxRate);
        }
        List<BigDecimal> exact = exactLineTaxes(taxRate);
        BigDecimal tax = zero();
        for (List<Integer> group : byRate(taxRate, true).values()) {
            tax = tax.add(roundTax(group.stream().map(exact::get).toList()));
        }
        return tax;
    }

    /**
     * The invoice's tax broken down by rate (a percentage), the lower rate first: for each rate, the amount
     * before tax of the lines and of the surcharge and minimum charge taxed at it, after the discounts (the
     * net when the client's prices include tax), and the tax worked out for that rate, as {@link #tax} does.
     * Untaxed lines and charges come under rate 0, and a reverse-charged invoice puts everything there. The
     * surcharge and minimum charge add no rate of their own when there are none.
     */
    public Map<BigDecimal, BigDecimal[]> taxByRate(BigDecimal taxRate) {
        Map<BigDecimal, BigDecimal[]> result = new TreeMap<>();
        if (clientPricesIncludeTax && isReverseCharge()) {
            result.put(BigDecimal.ZERO, new BigDecimal[] { beforeTax(taxRate), zero() });
            return result;
        }
        List<BigDecimal> amounts = inclusiveAmounts();
        List<BigDecimal> exact = clientPricesIncludeTax ? null : exactLineTaxes(taxRate);
        for (Map.Entry<BigDecimal, List<Integer>> group : byRate(taxRate, !clientPricesIncludeTax).entrySet()) {
            List<Integer> indexes = group.getValue();
            BigDecimal amount = zero();
            for (int i : indexes) {
                amount = amount.add(amounts.get(i));
            }
            if (indexes.size() == 1 && indexes.get(0) == lines.size() && amount.signum() == 0) {
                continue;
            }
            BigDecimal rate = group.getKey();
            BigDecimal tax = clientPricesIncludeTax
                    ? (rate.signum() == 0 ? zero() : inclusiveRateTax(amounts, rate, indexes))
                    : roundTax(indexes.stream().map(exact::get).toList());
            result.put(rate, new BigDecimal[] { clientPricesIncludeTax ? amount.subtract(tax) : amount, tax });
        }
        return result;
    }

    /**
     * Each line's tax taken out of tax-inclusive prices, in the order of the lines; none when the prices
     * do not include tax. A reverse-charged invoice has it taken out as usual, though it is not charged.
     */
    public List<BigDecimal> lineTaxesInside(BigDecimal taxRate) {
        return clientPricesIncludeTax ? inclusiveLineTaxes(taxRate) : lines.stream().map(l -> zero()).toList();
    }

    /** Whether the invoice's client is quoted prices that include tax. */
    public boolean isPricesIncludeTax() {
        return clientPricesIncludeTax;
    }

    /**
     * The tax-inclusive amount of each line after its discount share, in the order of the lines, then
     * the surcharge and minimum charge.
     */
    private List<BigDecimal> inclusiveAmounts() {
        List<BigDecimal> shares = discountShares();
        List<BigDecimal> amounts = new ArrayList<>(lines.size() + 1);
        for (int i = 0; i < lines.size(); i++) {
            amounts.add(lines.get(i).amount(scale()).subtract(shares.get(i)));
        }
        amounts.add(surcharge().add(minimumCharge()));
        return amounts;
    }

    /** The tax inside a tax-inclusive amount at the given rate: the amount less it divided by 1 plus the rate, rounded half up. */
    private BigDecimal taxInside(BigDecimal amount, BigDecimal rate) {
        return amount.subtract(amount.divide(BigDecimal.ONE.add(rate.movePointLeft(2)), scale(), RoundingMode.HALF_UP));
    }

    /**
     * The tax inside the total of one rate's tax-inclusive amounts (by index); in a region that requires
     * it, the exact tax inside rounded down to the cent instead.
     */
    private BigDecimal inclusiveRateTax(List<BigDecimal> amounts, BigDecimal rate, List<Integer> group) {
        BigDecimal taxed = zero();
        for (int i : group) {
            taxed = taxed.add(amounts.get(i));
        }
        if (regionRoundsDown) {
            return taxed.multiply(rate).divide(BigDecimal.valueOf(100).add(rate), scale(), RoundingMode.DOWN);
        }
        return taxInside(taxed, rate);
    }

    /**
     * The tax inside tax-inclusive prices: for each rate (a line's own, otherwise the client's), the tax
     * inside the total of the lines' amounts after their discount shares at that rate (with the
     * surcharge and the minimum charge at the client's rate), rounded half up to the cent (down in a
     * region that requires it); the invoice
     * tax is the total over the rates.
     */
    private BigDecimal inclusiveTax(BigDecimal taxRate) {
        List<BigDecimal> amounts = inclusiveAmounts();
        BigDecimal tax = zero();
        for (Map.Entry<BigDecimal, List<Integer>> group : byRate(taxRate, false).entrySet()) {
            tax = tax.add(inclusiveRateTax(amounts, group.getKey(), group.getValue()));
        }
        return tax;
    }

    /**
     * Each line's tax inside tax-inclusive prices, in the order of the lines: the tax inside the line's
     * amount after its discount share at the line's rate; exempt lines have none. For each rate, the
     * cents by which its lines miss the tax inside their total go (or come off) one at a time to its
     * lines with the largest amounts, the earlier line on a tie.
     */
    private List<BigDecimal> inclusiveLineTaxes(BigDecimal taxRate) {
        List<BigDecimal> amounts = inclusiveAmounts();
        int n = lines.size();
        List<BigDecimal> taxes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            taxes.add(zero());
        }
        BigDecimal cent = BigDecimal.ONE.movePointLeft(scale());
        for (Map.Entry<BigDecimal, List<Integer>> group : byRate(taxRate, false).entrySet()) {
            BigDecimal rate = group.getKey();
            if (rate.signum() == 0) {
                continue;
            }
            BigDecimal given = zero();
            List<Integer> order = new ArrayList<>();
            for (int i : group.getValue()) {
                BigDecimal tax = taxInside(amounts.get(i), rate);
                // The surcharge and minimum charge keep their own tax, which is not a line's.
                if (i < n) {
                    taxes.set(i, tax);
                    order.add(i);
                }
                given = given.add(tax);
            }
            order.sort(Comparator.<Integer, BigDecimal>comparing(amounts::get).reversed().thenComparing(i -> i));
            int missing = inclusiveRateTax(amounts, rate, group.getValue()).subtract(given).movePointRight(scale()).intValueExact();
            BigDecimal step = missing < 0 ? cent.negate() : cent;
            for (int k = 0; k < Math.abs(missing) && !order.isEmpty(); k++) {
                int i = order.get(k % order.size());
                taxes.set(i, taxes.get(i).add(step));
            }
        }
        return taxes;
    }

    /** The amount after the discounts: the subtotal less the discount. */
    public BigDecimal discounted() {
        return subtotal().subtract(discount());
    }

    /**
     * The rush surcharge: the surcharge percentage of the amount after the discounts, rounded down to
     * the cent. It is not discounted and is taxed at the client's rate. A credit note carries its
     * original's percentage on what it credits, so gives back the surcharge on that part.
     */
    public BigDecimal surcharge() {
        return discounted().multiply(chargedSurchargePct()).movePointLeft(2).setScale(scale(), RoundingMode.DOWN);
    }

    /**
     * The minimum charge: what the subtotal less the discount plus the surcharge falls short of the client's own
     * minimum charge (in its currency), or else the workspace's minimum charge, converted from US dollars at the
     * pricing date's rate (see {@link #pricingDate}) and rounded down to the step. Nothing when no minimum is set (or the client's is 0), for a
     * credit note, or for an invoice with no lines.
     */
    public BigDecimal minimumCharge() {
        if ((clientMinimumCharge == null && minimumChargeUsd == null) || isCreditNote() || lines.isEmpty()) {
            return zero();
        }
        BigDecimal minimum = clientMinimumCharge != null ? currency().divideDownToStep(clientMinimumCharge, BigDecimal.ONE)
                : currency().divideDownToStep(new BigDecimal(minimumChargeUsd.trim()), usdPerOn(pricingDate()));
        return minimum.subtract(discounted().add(surcharge())).max(zero());
    }

    /**
     * The amount before tax: the subtotal less the discount plus the surcharge and the minimum charge, less the tax inside
     * it when the client's prices include tax (taken out even when reverse charged).
     */
    public BigDecimal beforeTax(BigDecimal taxRate) {
        BigDecimal amount = discounted().add(surcharge()).add(minimumCharge());
        return clientPricesIncludeTax ? amount.subtract(inclusiveTax(taxRate)) : amount;
    }

    /**
     * The grand total: the subtotal less the discount plus the surcharge and the minimum charge, plus tax at the given
     * rate — or, when the client's prices include tax, that amount, which already holds it; a reverse-charged
     * client whose prices include tax pays only the amount before tax.
     */
    public BigDecimal total(BigDecimal taxRate) {
        BigDecimal amount = discounted().add(surcharge()).add(minimumCharge());
        if (clientPricesIncludeTax) {
            return isReverseCharge() ? beforeTax(taxRate) : amount;
        }
        return amount.add(tax(taxRate));
    }
}
