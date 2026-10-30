package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A plan's pause: from the day it was paused up to, not including, the day it was resumed (null while still paused).
 * The plan's dates within it are skipped.
 */
@Entity
@Table(name = "sheet_plan_pause")
public class PlanPause {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "paused_on", nullable = false)
    private LocalDate pausedOn;

    @Column(name = "resumed_on")
    private LocalDate resumedOn;

    protected PlanPause() {
    }

    public PlanPause(Long planId, LocalDate pausedOn) {
        this.planId = planId;
        this.pausedOn = pausedOn;
    }

    public Long getPlanId() {
        return planId;
    }

    public LocalDate getPausedOn() {
        return pausedOn;
    }

    /** The day it was resumed, or null while still paused. */
    public LocalDate getResumedOn() {
        return resumedOn;
    }

    public void resume(LocalDate on) {
        this.resumedOn = on;
    }

    /** Whether the plan date falls within the pause, and so is skipped. */
    public boolean skips(LocalDate on) {
        return !on.isBefore(pausedOn) && (resumedOn == null || on.isBefore(resumedOn));
    }
}
