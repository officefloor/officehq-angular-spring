package net.officefloor.hq.app.clients;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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

    /** The currency the client is billed in: everything on its invoices is in it. */
    @Enumerated(EnumType.STRING)
    private Currency currency = Currency.USD;

    /** The percentage of the grand total taken off an invoice paid off early; 0 is none. */
    @Column(name = "early_pay_pct")
    private BigDecimal earlyPayPct = BigDecimal.ZERO;

    /** How many days after its issue date an invoice counts as paid off early; null when never. */
    @Column(name = "early_pay_days")
    private Integer earlyPayDays;

    /** The standard retention percentage (0 to 20) new invoices for this client start with; 0 is none. */
    @Column(name = "retention_pct")
    private BigDecimal retentionPct = BigDecimal.ZERO;

    /**
     * Days past its due date an invoice or instalment for this client is given before it is overdue,
     * earns late interest and is charged the late fee: 0 to {@link #MAX_GRACE_DAYS}, a week as standard.
     */
    @Column(name = "grace_days")
    private int graceDays = Invoice.GRACE_DAYS;

    /**
     * The client's own annual late-interest rate (0 to {@link #MAX_INTEREST_RATE} percent) charged
     * instead of the workspace's; null for the workspace's, 0 for never charged late interest.
     */
    @Column(name = "interest_rate")
    private BigDecimal interestRate;

    /** The highest late-interest rate a client can be given, as a percentage. */
    public static final int MAX_INTEREST_RATE = 30;

    /** The longest grace period a client can be given, in days. */
    public static final int MAX_GRACE_DAYS = 30;

    protected Client() {
    }

    public Client(String name, String email, Integer termsDays, BigDecimal discountPct, boolean taxExempt,
            Currency currency, BigDecimal earlyPayPct, Integer earlyPayDays, BigDecimal retentionPct,
            Integer graceDays, BigDecimal interestRate) {
        this.name = name;
        this.email = email;
        this.termsDays = termsDays;
        this.discountPct = discountPct == null ? BigDecimal.ZERO : discountPct;
        this.taxExempt = taxExempt;
        this.currency = currency == null ? Currency.USD : currency;
        this.earlyPayPct = earlyPayPct == null ? BigDecimal.ZERO : earlyPayPct;
        this.earlyPayDays = earlyPayDays;
        this.retentionPct = retentionPct == null ? BigDecimal.ZERO : retentionPct;
        this.graceDays = graceDays == null ? Invoice.GRACE_DAYS : graceDays;
        this.interestRate = interestRate;
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

    public int getGraceDays() {
        return graceDays;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }
}
