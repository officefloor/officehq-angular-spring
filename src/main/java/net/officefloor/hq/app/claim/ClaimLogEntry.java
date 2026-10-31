package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One line of a claim's status log: the status it took, who moved it there, the sheet's today then and the due
 *  it had at that moment (none when it had no figure). */
@Embeddable
public class ClaimLogEntry {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "by_user", nullable = false, length = 100)
    private String by;

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

    @Column(name = "due", precision = 14, scale = 2)
    private BigDecimal due;

    protected ClaimLogEntry() {
    }

    public ClaimLogEntry(ClaimStatus status, String by, LocalDate on, BigDecimal due) {
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
