package net.officefloor.hq.app.client;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import net.officefloor.hq.app.contact.Contact;
import net.officefloor.hq.app.currency.Currency;

/** A client of the office. */
@Entity
@Table(name = "client")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String email;

    /** A number to reach the client on; none when not known. */
    private String phone;

    /** The client's tax registration number; none when they are not tax registered. */
    private String taxNumber;

    /** Where the client's bills are sent; none when not known. */
    private String billingAddress;

    /** The language the client prefers to be dealt with in; none when not known. */
    private String language;

    /** The person in the office who looks after the client; none when not recorded. */
    private String accountManager;

    /** Who the client's bills should go to, when someone other than the client themselves; none when not recorded. */
    private String billingContact;

    private boolean archived;

    /** Whether the client's prices already include tax, so tax is worked back out of them rather than added on. */
    private boolean taxInclusive;

    /** Whether the client is tax exempt, so none of their invoices carry any tax whatever the lines say. */
    private boolean taxExempt;

    /** Whether the client is one of the office's key accounts, marked out wherever they are listed. */
    private boolean keyAccount;

    /** The client's standard discount, a percentage each new invoice for them starts with; zero when none. */
    @Column(name = "default_discount_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal defaultDiscountPct = BigDecimal.ZERO.setScale(2);

    /** The most the client may owe, in their currency; none when no limit is set. */
    @Column(precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /** The number of days the client has to pay an invoice (e.g. 30 for net 30); none when no terms are agreed. */
    private Integer paymentTermsDays;

    /**
     * Part of the client's payment terms: the number of days after an invoice is issued within which paying it
     * earns its early-payment discount; within the payment terms, and none when not agreed.
     */
    private Integer earlyPaymentWindowDays;

    /** The currency the client is billed in; all of their money is in it. */
    @Column(nullable = false)
    private String currency = Currency.DEFAULT;

    /** The client's main contact, one of its own contacts; none until one is chosen. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_contact_id")
    private Contact primaryContact;

    protected Client() {
    }

    public Client(String name, String email, String phone, String taxNumber, String billingAddress) {
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.taxNumber = taxNumber;
        this.billingAddress = billingAddress;
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

    public String getPhone() {
        return phone;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getAccountManager() {
        return accountManager;
    }

    public void setAccountManager(String accountManager) {
        this.accountManager = accountManager;
    }

    public String getBillingContact() {
        return billingContact;
    }

    public void setBillingContact(String billingContact) {
        this.billingContact = billingContact;
    }

    public String getTaxNumber() {
        return taxNumber;
    }

    public String getBillingAddress() {
        return billingAddress;
    }

    /** Corrects the client's name, email, phone number, tax number and billing address. */
    public void rename(String name, String email, String phone, String taxNumber, String billingAddress) {
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.taxNumber = taxNumber;
        this.billingAddress = billingAddress;
    }

    public boolean isTaxInclusive() {
        return taxInclusive;
    }

    public void setTaxInclusive(boolean taxInclusive) {
        this.taxInclusive = taxInclusive;
    }

    public boolean isTaxExempt() {
        return taxExempt;
    }

    public void setTaxExempt(boolean taxExempt) {
        this.taxExempt = taxExempt;
    }

    public boolean isKeyAccount() {
        return keyAccount;
    }

    public void setKeyAccount(boolean keyAccount) {
        this.keyAccount = keyAccount;
    }

    public BigDecimal getDefaultDiscountPct() {
        return defaultDiscountPct;
    }

    public void setDefaultDiscountPct(BigDecimal defaultDiscountPct) {
        this.defaultDiscountPct = defaultDiscountPct.setScale(2, RoundingMode.HALF_UP);
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit == null ? null : creditLimit.setScale(2, RoundingMode.HALF_UP);
    }

    public Integer getPaymentTermsDays() {
        return paymentTermsDays;
    }

    public void setPaymentTermsDays(Integer paymentTermsDays) {
        this.paymentTermsDays = paymentTermsDays;
    }

    public Integer getEarlyPaymentWindowDays() {
        return earlyPaymentWindowDays;
    }

    public void setEarlyPaymentWindowDays(Integer earlyPaymentWindowDays) {
        this.earlyPaymentWindowDays = earlyPaymentWindowDays;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Contact getPrimaryContact() {
        return primaryContact;
    }

    public void setPrimaryContact(Contact primaryContact) {
        this.primaryContact = primaryContact;
    }
}
