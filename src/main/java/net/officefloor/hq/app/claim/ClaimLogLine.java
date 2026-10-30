package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A line of a claim's status log: the status it took, by whom, on the sheet's today then, and its due then. */
@Embeddable
public class ClaimLogLine {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

    /** The claim's due as computed then; null when it had none. */
    @Column(name = "due", precision = 14, scale = 2)
    private BigDecimal due;

    protected ClaimLogLine() {
    }

    public ClaimLogLine(ClaimStatus status, String by, LocalDate on, BigDecimal due) {
        this.status = status;
        this.by = by;
        this.on = on;
        this.due = due;
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
