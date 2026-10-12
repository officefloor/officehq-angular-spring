package net.officefloor.hq.app.clients;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A client invoices are raised for. */
@Entity
@Table(name = "client")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String email;

    /** Payment terms in days; null means the usual end of the following month. */
    private Integer termsDays;

    /** The standard percentage discount new invoices for this client start with; 0 is none. */
    @Column(name = "discount_pct")
    private BigDecimal discountPct = BigDecimal.ZERO;

    /** Whether the client is tax exempt (a charity, say): its invoices charge no tax on any line. */
    @Column(name = "tax_exempt")
    private boolean taxExempt;

    /**
     * The last issue date the client's tax exemption covers: its invoices issued after it are taxed as
     * usual; null when the exemption does not end, and always for a client that is not exempt.
     */
    @Column(name = "tax_exempt_until")
    private LocalDate taxExemptUntil;

    /** Whether the client is quoted prices that include tax: the tax is taken out of its invoice amounts. */
    @Column(name = "prices_include_tax")
    private boolean pricesIncludeTax;

    /** The currency the client is billed in: everything on its invoices is in it. */
    @Enumerated(EnumType.STRING)
    private Currency currency = Currency.USD;

    /** The percentage of the amount before tax taken off an invoice paid off early; 0 is none. */
    @Column(name = "early_pay_pct")
    private BigDecimal earlyPayPct = BigDecimal.ZERO;

    /** How many days after its issue date an invoice counts as paid off early; null when never. */
    @Column(name = "early_pay_days")
    private Integer earlyPayDays;

    /** The standard retention percentage (0 to 20) new invoices for this client start with; 0 is none. */
    @Column(name = "retention_pct")
    private BigDecimal retentionPct = BigDecimal.ZERO;

    /**
     * The percentage (0 to 100) of each invoice's amount before tax the client withholds and pays to its
     * tax office; 0 is none. It is never owed by the client.
     */
    @Column(name = "withholding_pct")
    private BigDecimal withholdingPct = BigDecimal.ZERO;

    /**
     * Days past its due date an invoice or instalment for this client is given before it is overdue,
     * and earns late interest: 0 to {@link #MAX_GRACE_DAYS}; null for their region's, else a week.
     */
    @Column(name = "grace_days")
    private Integer graceDays;

    /**
     * The client's own annual late-interest rate (0 to {@link #MAX_INTEREST_RATE} percent) charged
     * instead of the workspace's; null for the workspace's, 0 for never charged late interest.
     */
    @Column(name = "interest_rate")
    private BigDecimal interestRate;

    /**
     * The region the client is in (a state, say): its invoices are taxed at the region's rate from the
     * workspace's regional rates when one is listed; null for no region.
     */
    private String region;

    /**
     * The client's tax number, without spaces (see {@link TaxNumber}): with a valid one from its region
     * outside the workspace's home region its invoices are reverse charged; null for no number.
     */
    @Column(name = "tax_number")
    private String taxNumber;

    /**
     * The client's own minimum charge, in its currency (no conversion), charged instead of the
     * workspace's on its invoices; null for the workspace's, 0 for never charged a minimum.
     */
    @Column(name = "minimum_charge")
    private BigDecimal minimumCharge;

    /**
     * The most the client may owe, in its currency: an invoice that would take its balance over it is not
     * sent; null for no limit.
     */
    @Column(name = "credit_limit")
    private BigDecimal creditLimit;

    /**
     * Whether the client is on hold: their invoices are not sent, their credit is not applied, and their
     * payments pay no invoice but are all held as credit.
     */
    @Column(name = "on_hold")
    private boolean onHold;

    /** The client's tier: its approval limit is the tier's when the workspace gives the tier one. */
    @Enumerated(EnumType.STRING)
    private ClientTier tier = ClientTier.STANDARD;

    /**
     * The percentage (0 to 100, 0 for none) taken off every price the client's lines take from its rate card or the
     * price list (see {@link RateDiscount}).
     */
    @Column(name = "rate_discount_pct")
    private BigDecimal rateDiscountPct = BigDecimal.ZERO;

    /**
     * The client's rate card: its prices, single or tiered, per unit type (hour, day, ...), each from a date or
     * always; empty for none.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "client_rate", joinColumns = @JoinColumn(name = "client_id"))
    private List<ClientRate> rates = new ArrayList<>();

    /** The highest late-interest rate a client can be given, as a percentage. */
    public static final int MAX_INTEREST_RATE = 30;

    /** The longest grace period a client can be given, in days. */
    public static final int MAX_GRACE_DAYS = 30;

    protected Client() {
    }

    public Client(String name, String email, Integer termsDays, BigDecimal discountPct, boolean taxExempt,
            boolean pricesIncludeTax, Currency currency, BigDecimal earlyPayPct, Integer earlyPayDays, BigDecimal retentionPct,
            Integer graceDays, BigDecimal interestRate, String region, String taxNumber, BigDecimal withholdingPct, BigDecimal minimumCharge,
            BigDecimal rateDiscountPct, LocalDate taxExemptUntil, BigDecimal creditLimit, ClientTier tier) {
        this.name = name;
        this.tier = tier == null ? ClientTier.STANDARD : tier;
        this.creditLimit = creditLimit;
        this.taxExemptUntil = taxExemptUntil;
        this.email = email;
        this.termsDays = termsDays;
        this.discountPct = discountPct == null ? BigDecimal.ZERO : discountPct;
        this.taxExempt = taxExempt;
        this.pricesIncludeTax = pricesIncludeTax;
        this.currency = currency == null ? Currency.USD : currency;
        this.earlyPayPct = earlyPayPct == null ? BigDecimal.ZERO : earlyPayPct;
        this.earlyPayDays = earlyPayDays;
        this.retentionPct = retentionPct == null ? BigDecimal.ZERO : retentionPct;
        this.graceDays = graceDays;
        this.interestRate = interestRate;
        this.region = region;
        this.taxNumber = taxNumber;
        this.withholdingPct = withholdingPct == null ? BigDecimal.ZERO : withholdingPct;
        this.minimumCharge = minimumCharge;
        this.rateDiscountPct = rateDiscountPct == null ? BigDecimal.ZERO : rateDiscountPct;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public Integer getTermsDays() {
        return termsDays;
    }

    public BigDecimal getDiscountPct() {
        return discountPct;
    }

    public boolean isTaxExempt() {
        return taxExempt;
    }

    public LocalDate getTaxExemptUntil() {
        return taxExemptUntil;
    }

    public boolean isPricesIncludeTax() {
        return pricesIncludeTax;
    }

    public Currency getCurrency() {
        return currency;
    }

    public BigDecimal getEarlyPayPct() {
        return earlyPayPct;
    }

    public Integer getEarlyPayDays() {
        return earlyPayDays;
    }

    public BigDecimal getRetentionPct() {
        return retentionPct;
    }

    public BigDecimal getWithholdingPct() {
        return withholdingPct;
    }

    public BigDecimal getRateDiscountPct() {
        return rateDiscountPct;
    }

    public BigDecimal getMinimumCharge() {
        return minimumCharge;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public ClientTier getTier() {
        return tier;
    }

    public boolean isOnHold() {
        return onHold;
    }

    /** Puts the client on hold, or lifts the hold. */
    public void setOnHold(boolean onHold) {
        this.onHold = onHold;
    }

    public Integer getGraceDays() {
        return graceDays;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public String getRegion() {
        return region;
    }

    public String getTaxNumber() {
        return taxNumber;
    }

    public List<ClientRate> getRates() {
        return rates;
    }

    /**
     * The client's rate for the given unit type (matched ignoring case and surrounding spaces) in force on the date:
     * the latest entry from on or before it, an entry with no date before any dated one; null when none is.
     */
    public ClientRate rateFor(String unitType, LocalDate on) {
        return rates.stream().filter(r -> r.isFor(unitType) && r.inForceOn(on))
                .max(Comparator.comparing(ClientRate::getEffectiveFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }
}
