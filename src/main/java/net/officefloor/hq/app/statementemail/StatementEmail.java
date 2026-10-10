package net.officefloor.hq.app.statementemail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One time a client's statement was emailed: the address it went to and when. */
@Entity
@Table(name = "statement_email")
public class StatementEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false)
    private String email;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected StatementEmail() {
    }

    public StatementEmail(Long clientId, String email, Instant sentAt) {
        this.clientId = clientId;
        this.email = email;
        this.sentAt = sentAt;
    }

    public Long getId() {
        return id;
    }

    public Long getClientId() {
        return clientId;
    }

    public String getEmail() {
        return email;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
