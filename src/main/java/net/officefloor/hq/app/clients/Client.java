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
import jakarta.persistence.MapKeyColumn;
import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.invoices.Invoice;

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
     * and earns late interest: 0 to {@link #MAX_GRACE_DAYS}, a week as standard.
     */
    @Column(name = "grace_days")
    private int graceDays = Invoice.GRACE_DAYS;

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

    /** The client's rate card: its price, single or tiered, per unit type (hour, day, ...); empty for none. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "client_rate", joinColumns = @JoinColumn(name = "client_id"))
    @MapKeyColumn(name = "unit_type")
    private Map<String, ClientRate> rates = new TreeMap<>();

    /** The highest late-interest rate a client can be given, as a percentage. */
    public static final int MAX_INTEREST_RATE = 30;

    /** The longest grace period a client can be given, in days. */
    public static final int MAX_GRACE_DAYS = 30;

    protected Client() {
    }

    public Client(String name, String email, Integer termsDays, BigDecimal discountPct, boolean taxExempt,
            boolean pricesIncludeTax, Currency currency, BigDecimal earlyPayPct, Integer earlyPayDays, BigDecimal retentionPct,
            Integer graceDays, BigDecimal interestRate, String region, String taxNumber, BigDecimal withholdingPct, BigDecimal minimumCharge) {
        this.name = name;
        this.email = email;
        this.termsDays = termsDays;
        this.discountPct = discountPct == null ? BigDecimal.ZERO : discountPct;
        this.taxExempt = taxExempt;
        this.pricesIncludeTax = pricesIncludeTax;
        this.currency = currency == null ? Currency.USD : currency;
        this.earlyPayPct = earlyPayPct == null ? BigDecimal.ZERO : earlyPayPct;
        this.earlyPayDays = earlyPayDays;
        this.retentionPct = retentionPct == null ? BigDecimal.ZERO : retentionPct;
        this.graceDays = graceDays == null ? Invoice.GRACE_DAYS : graceDays;
        this.interestRate = interestRate;
        this.region = region;
        this.taxNumber = taxNumber;
        this.withholdingPct = withholdingPct == null ? BigDecimal.ZERO : withholdingPct;
        this.minimumCharge = minimumCharge;
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

    public BigDecimal getMinimumCharge() {
        return minimumCharge;
    }

    public int getGraceDays() {
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

    public Map<String, ClientRate> getRates() {
        return rates;
    }

    /** The client's rate for the given unit type (matched ignoring case and surrounding spaces); null when not on its card. */
    public ClientRate rateFor(String unitType) {
        if (unitType == null) {
            return null;
        }
        return rates.entrySet().stream().filter(r -> r.getKey().equalsIgnoreCase(unitType.trim()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
}
