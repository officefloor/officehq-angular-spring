package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A pause of a plan: the day it was paused and the day it was resumed (null while still paused). Plan dates
 *  on or after the day paused and before the day resumed are skipped. */
@Embeddable
public class PlanPause {

    @Column(name = "paused_on", nullable = false)
    private LocalDate pausedOn;

    @Column(name = "resumed_on")
    private LocalDate resumedOn;

    protected PlanPause() {
    }

    PlanPause(LocalDate pausedOn) {
        this.pausedOn = pausedOn;
    }

    public LocalDate getPausedOn() {
        return pausedOn;
    }

    public LocalDate getResumedOn() {
        return resumedOn;
    }

    void resume(LocalDate on) {
        this.resumedOn = on;
    }

    /** Whether the plan date falls in the pause. */
    boolean skips(LocalDate date) {
        return !date.isBefore(pausedOn) && (resumedOn == null || date.isBefore(resumedOn));
    }
}
