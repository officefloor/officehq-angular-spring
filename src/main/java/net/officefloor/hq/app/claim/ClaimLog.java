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

/** A line of a claim's status log: a status it came to have, who made the change, the sheet's today
 *  then, and the claim's due as computed at that moment (null when it had none). */
@Entity
@Table(name = "claim_log")
public class ClaimLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "by_user", nullable = false)
    private String byUser;

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

    @Column(name = "due", precision = 14, scale = 2)
    private BigDecimal due;

    protected ClaimLog() {
    }

    public ClaimLog(String sheetId, ClaimStatus status, String byUser, LocalDate on, BigDecimal due) {
        this.sheetId = sheetId;
        this.status = status;
        this.byUser = byUser;
        this.on = on;
        this.due = due;
    }

    public Long getId() {
        return id;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public String getByUser() {
        return byUser;
    }

    public LocalDate getOn() {
        return on;
    }

    public BigDecimal getDue() {
        return due;
    }
}
