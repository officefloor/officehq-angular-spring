package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A line of a claim's status log: a status it was given, by whom, the sheet's today then, and its due
 * as computed at that moment (none when it had none).
 */
@Embeddable
public class ClaimLogEntry {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "by_user", nullable = false, length = 100)
    private String by;

    @Column(name = "log_date", nullable = false)
    private LocalDate date;

    @Column(name = "due", precision = 14, scale = 2)
    private BigDecimal due;

    protected ClaimLogEntry() {}

    public ClaimLogEntry(ClaimStatus status, String by, LocalDate date, BigDecimal due) {
        this.status = status;
        this.by = by;
        this.date = date;
        this.due = due;
    }

    public ClaimStatus getStatus() { return status; }

    public String getBy() { return by; }

    public LocalDate getDate() { return date; }

    public BigDecimal getDue() { return due; }
}
