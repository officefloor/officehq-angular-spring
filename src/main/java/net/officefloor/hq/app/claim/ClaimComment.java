package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A comment on a claim: its text, the user who wrote it and the sheet's today when it was written. */
@Entity
@Table(name = "claim_comment")
public class ClaimComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "on_date")
    private LocalDate on;

    @Column(name = "text", nullable = false)
    private String text;

    protected ClaimComment() {
    }

    public ClaimComment(String by, LocalDate on, String text) {
        this.by = by;
        this.on = on;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public String getBy() {
        return by;
    }

    public LocalDate getOn() {
        return on;
    }

    public String getText() {
        return text;
    }
}
