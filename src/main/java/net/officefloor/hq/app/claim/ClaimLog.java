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
import java.util.List;

/** A line of a claim's status log: a status it came to have, the user who made the change, the sheet's
 * today then and what was due on it as it showed then (null when it had no figure). */
@Entity
@Table(name = "claim_log")
public class ClaimLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "log_date", nullable = false)
    private LocalDate date;

    @Column(name = "due", precision = 14, scale = 2)
    private BigDecimal due;

    protected ClaimLog() {
    }

    public ClaimLog(Long claimId, ClaimStatus status, String by, LocalDate date, BigDecimal due) {
        this.claimId = claimId;
        this.status = status;
        this.by = by;
        this.date = date;
        this.due = due;
    }

    /** The same line, on the log of the claim {@code claimId}. */
    public ClaimLog copyFor(Long claimId) {
        return new ClaimLog(claimId, status, by, date, due);
    }

    /** The sheet's today when the claim whose log is {@code log} (oldest first) was opened: the date of the
     * first line showing it OPEN, else of the first showing it past DRAFT; null when it shows neither. */
    public static LocalDate openedOn(List<ClaimLog> log) {
        if (log == null) return null;
        return log.stream().filter(line -> line.getStatus() == ClaimStatus.OPEN).map(ClaimLog::getDate).findFirst()
                .orElseGet(() -> log.stream().filter(line -> line.getStatus() != ClaimStatus.DRAFT)
                        .map(ClaimLog::getDate).findFirst().orElse(null));
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

    public BigDecimal getDue() {
        return due;
    }
}
