package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** One line of a claim's status log: a status it took, who changed it, the sheet's today then, and its due then. */
@Entity
@Table(name = "claim_log")
public class ClaimLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "logged_on", nullable = false)
    private LocalDate date;

    /** The claim's due at that moment, two decimals, or n/a. */
    @Column(name = "due", nullable = false)
    private String due;

    protected ClaimLog() {
    }

    public ClaimLog(String sheetId, Long claimId, ClaimStatus status, String by, LocalDate date, String due) {
        this.sheetId = sheetId;
        this.claimId = claimId;
        this.status = status;
        this.by = by;
        this.date = date;
        this.due = due;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public String getBy() {
        return by;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getDue() {
        return due;
    }
}
