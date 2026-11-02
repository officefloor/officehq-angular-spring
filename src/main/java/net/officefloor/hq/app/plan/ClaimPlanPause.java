package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A pause of a plan: from the day it was paused up to the day it was resumed (none while still paused). The plan's
 * dates on or after its from-day and before its until-day are skipped.
 */
@Entity
@Table(name = "claim_plan_pause")
public class ClaimPlanPause {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "from_on", nullable = false)
    private LocalDate from;

    @Column(name = "until_on")
    private LocalDate until;

    protected ClaimPlanPause() {}

    public ClaimPlanPause(String sheetId, Long planId, LocalDate from) {
        this.sheetId = sheetId;
        this.planId = planId;
        this.from = from;
    }

    public Long getPlanId() { return planId; }

    public LocalDate getFrom() { return from; }

    public LocalDate getUntil() { return until; }

    /** Whether the plan is still paused. */
    public boolean isOpen() { return until == null; }

    public void resume(LocalDate on) { this.until = on; }

    /** Whether the plan date falls in the pause, and so is skipped. */
    public boolean skips(LocalDate date) {
        return !date.isBefore(from) && (until == null || date.isBefore(until));
    }
}
