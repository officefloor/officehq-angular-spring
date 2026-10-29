package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A pause of a plan from the day it was paused to the day it was resumed (null: not resumed yet). */
@Embeddable
public class PlanPause {

    @Column(name = "paused_on", nullable = false)
    private LocalDate pausedOn;

    @Column(name = "resumed_on")
    private LocalDate resumedOn;

    protected PlanPause() {
    }

    public PlanPause(LocalDate pausedOn) {
        this.pausedOn = pausedOn;
    }

    public LocalDate getPausedOn() {
        return pausedOn;
    }

    public LocalDate getResumedOn() {
        return resumedOn;
    }

    public boolean isOpen() {
        return resumedOn == null;
    }

    void resume(LocalDate on) {
        this.resumedOn = on;
    }

    /** Whether the plan date is skipped: on or after the day paused and before the day resumed. */
    public boolean skips(LocalDate date) {
        return !date.isBefore(pausedOn) && (resumedOn == null || date.isBefore(resumedOn));
    }
}
