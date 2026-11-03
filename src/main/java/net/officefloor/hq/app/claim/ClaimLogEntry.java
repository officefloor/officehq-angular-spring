package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDate;

/**
 * One line of a claim's status log: the status it took, the user who made the change, the sheet's
 * today then and the claim's due as computed at that moment ({@code n/a} when it had none).
 */
@Embeddable
public class ClaimLogEntry {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "log_date", nullable = false)
    private LocalDate date;

    @Column(name = "due", nullable = false)
    private String due;

    protected ClaimLogEntry() {
    }

    public ClaimLogEntry(ClaimStatus status, String by, LocalDate date, String due) {
        this.status = status;
        this.by = by;
        this.date = date;
        this.due = due;
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
