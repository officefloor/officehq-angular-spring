package net.officefloor.hq.app.client;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import net.officefloor.hq.app.contact.Contact;

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

    private boolean archived;

    /** Whether the client's prices already include tax, so tax is worked back out of them rather than added on. */
    private boolean taxInclusive;

    /** Whether the client is tax exempt, so none of their invoices carry any tax whatever the lines say. */
    private boolean taxExempt;

    /** The currency the client is billed in; all of their money is in it. */
    @Enumerated(EnumType.STRING)
    private Currency currency = Currency.USD;

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

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Currency getCurrency() {
        return currency;
    }

    public void setCurrency(Currency currency) {
        this.currency = currency;
    }

    public Contact getPrimaryContact() {
        return primaryContact;
    }

    public void setPrimaryContact(Contact primaryContact) {
        this.primaryContact = primaryContact;
    }
}
