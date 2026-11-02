package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A comment on a claim: who wrote it, the sheet's today then, and its text. */
@Entity
@Table(name = "claim_comment")
public class ClaimComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "by_user", nullable = false)
    private String byUser;

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

    @Column(name = "text", nullable = false)
    private String text;

    protected ClaimComment() {
    }

    public ClaimComment(String sheetId, String byUser, LocalDate on, String text) {
        this.sheetId = sheetId;
        this.byUser = byUser;
        this.on = on;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public String getByUser() {
        return byUser;
    }

    public LocalDate getOn() {
        return on;
    }

    public String getText() {
        return text;
    }
}
