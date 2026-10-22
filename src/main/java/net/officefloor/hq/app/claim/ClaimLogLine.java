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
 * A line of a claim's status log: a status the claim moved to, by whom, on the sheet's today then,
 * and the claim's due as computed at that moment (null when n/a).
 */
@Entity
@Table(name = "claim_log")
public class ClaimLogLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

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
