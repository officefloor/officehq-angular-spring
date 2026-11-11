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

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "written_on", nullable = false)
    private LocalDate date;

    @Column(name = "text", nullable = false)
    private String text;

    protected ClaimComment() {
    }

    public ClaimComment(String sheetId, Long claimId, String by, LocalDate date, String text) {
        this.sheetId = sheetId;
        this.claimId = claimId;
        this.by = by;
        this.date = date;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public String getBy() {
        return by;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getText() {
        return text;
    }
}
