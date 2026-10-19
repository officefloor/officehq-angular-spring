package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDate;

/** One line of a claim's status log: the status it took, who changed it, the sheet's today then, and the claim's
 * due as computed at that moment, with two decimals ("n/a" when it had none). */
@Embeddable
public class StatusLogLine {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

    @Column(name = "due", nullable = false)
    private String due;

    protected StatusLogLine() {}

    public StatusLogLine(ClaimStatus status, String by, LocalDate on, String due) {
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

    public String getDue() {
        return due;
    }
}
