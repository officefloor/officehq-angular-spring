package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A comment on a claim: who wrote it, the sheet's today when they did, and its text. */
@Entity
@Table(name = "claim_comment")
public class ClaimComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false, updatable = false)
    private String sheetId;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Column(name = "written_by", nullable = false, updatable = false)
    private String by;

    @Column(name = "written_on", nullable = false, updatable = false)
    private LocalDate on;

    @Column(name = "text", nullable = false, updatable = false)
    private String text;

    protected ClaimComment() {
    }

    public ClaimComment(String sheetId, Long claimId, String by, LocalDate on, String text) {
        this.sheetId = sheetId;
        this.claimId = claimId;
        this.by = by;
        this.on = on;
        this.text = text;
    }

    public Long getClaimId() {
        return claimId;
    }

    public LocalDate getOn() {
        return on;
    }

    public String getText() {
        return text;
    }

    /** The comment as the claim shows it: <user> on <date>: <text>. */
    public String line() {
        return by + " on " + on + ": " + text;
    }
}
