package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A line of a claim's status log: a status the claim took, who made the change, the sheet's today
 * then, and the claim's due as computed at that moment (none if its grade had no rate).
 */
@Entity
@Table(name = "claim_status_log")
public class ClaimStatusLog {

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

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

    @Column(name = "due", precision = 14, scale = 2)
    private BigDecimal due;

    protected ClaimStatusLog() {
    }

    public ClaimStatusLog(String sheetId, Long claimId, ClaimStatus status, String by, LocalDate on, BigDecimal due) {
        this.sheetId = sheetId;
        this.claimId = claimId;
        this.status = status;
        this.by = by;
        this.on = on;
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

    public LocalDate getOn() {
        return on;
    }

    public BigDecimal getDue() {
        return due;
    }
}
