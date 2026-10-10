package net.officefloor.hq.app.contacthistory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** One time a client was contacted: the date and a note of what it was about. */
@Entity
@Table(name = "contact_history")
public class ContactHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "contact_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private String note;

    protected ContactHistoryEntry() {
    }

    public ContactHistoryEntry(Long clientId, LocalDate date, String note) {
        this.clientId = clientId;
        this.date = date;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public Long getClientId() {
        return clientId;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getNote() {
        return note;
    }
}
