package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A time a plan was paused: from the day it was paused up to the day it was resumed, or open while still paused. */
@Entity
@Table(name = "plan_pause")
public class PlanPause {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "paused_on", nullable = false)
    private LocalDate pausedOn;

    @Column(name = "resumed_on")
    private LocalDate resumedOn;

    protected PlanPause() {
    }

    public PlanPause(String sheetId, Long planId, LocalDate pausedOn) {
        this.sheetId = sheetId;
        this.planId = planId;
        this.pausedOn = pausedOn;
    }

    /** Whether the plan is still paused. */
    public boolean isOpen() {
        return resumedOn == null;
    }

    /** Whether a plan date on the given day is skipped by this pause. */
    public boolean skips(LocalDate date) {
        return !date.isBefore(pausedOn) && (resumedOn == null || date.isBefore(resumedOn));
    }

    public void resume(LocalDate on) {
        this.resumedOn = on;
    }

    public Long getId() {
        return id;
    }

    public Long getPlanId() {
        return planId;
    }

    public LocalDate getPausedOn() {
        return pausedOn;
    }

    public LocalDate getResumedOn() {
        return resumedOn;
    }
}
