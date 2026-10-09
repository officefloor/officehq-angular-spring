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

    private boolean archived;

    /** The currency the client is billed in; all of their money is in it. */
    @Enumerated(EnumType.STRING)
    private Currency currency = Currency.USD;

    /** The client's main contact, one of its own contacts; none until one is chosen. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_contact_id")
    private Contact primaryContact;

    protected Client() {
    }

    public Client(String name, String email, String phone) {
        this.name = name;
        this.email = email;
        this.phone = phone;
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

    /** Corrects the client's name, email and phone number. */
    public void rename(String name, String email, String phone) {
        this.name = name;
        this.email = email;
        this.phone = phone;
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
