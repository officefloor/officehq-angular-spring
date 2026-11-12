package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A line of a claim's status log: the status it took, who made the change, the sheet's today then, and
 *  the claim's due as it was computed at that moment (none when it was n/a). */
@Embeddable
public class StatusChange {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

    @Column(name = "due")
    private BigDecimal due;

    protected StatusChange() {
    }

    public StatusChange(ClaimStatus status, String by, LocalDate on, BigDecimal due) {
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
