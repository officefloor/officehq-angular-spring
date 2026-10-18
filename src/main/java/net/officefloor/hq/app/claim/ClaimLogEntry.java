package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One line of a claim's status log: a status it took, who gave it, the sheet's today then and its due then. */
@Embeddable
public class ClaimLogEntry {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "logged_on", nullable = false)
    private LocalDate on;

    /** The claim's due as it was worked out then; none when it was n/a. */
    @Column(name = "due", precision = 15, scale = 2)
    private BigDecimal due;

    protected ClaimLogEntry() {}

    public ClaimLogEntry(ClaimStatus status, String by, LocalDate on, BigDecimal due) {
        this.status = status;
        this.by = by;
        this.on = on;
        this.due = due;
    }

    public ClaimStatus getStatus() { return status; }

    public String getBy() { return by; }

    public LocalDate getOn() { return on; }

    public BigDecimal getDue() { return due; }
}
