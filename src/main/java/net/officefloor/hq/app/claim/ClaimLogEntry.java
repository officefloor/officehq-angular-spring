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

/** One line of a claim's status log: a status it moved to, who moved it, the sheet's today when they
 *  did, and the claim's due as it was computed then (two decimals, or n/a when it had none). */
@Entity
@Table(name = "claim_status_log")
public class ClaimLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false, updatable = false)
    private String sheetId;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private ClaimStatus status;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private String by;

    @Column(name = "changed_on", nullable = false, updatable = false)
    private LocalDate on;

    @Column(name = "due", nullable = false, updatable = false)
    private String due;

    protected ClaimLogEntry() {
    }

    public ClaimLogEntry(String sheetId, Long claimId, ClaimStatus status, String by, LocalDate on, String due) {
        this.sheetId = sheetId;
        this.claimId = claimId;
        this.status = status;
        this.by = by;
        this.on = on;
        this.due = due;
    }

    public Long getClaimId() {
        return claimId;
    }

    /** The line as the claim shows it: <STATUS> by <user> on <date> due=<due>. */
    public String text() {
        return status + " by " + by + " on " + on + " due=" + due;
    }
}
