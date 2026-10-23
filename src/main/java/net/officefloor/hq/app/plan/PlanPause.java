package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A pause of a plan: the day it was paused, and the day it was resumed, or null while it is still paused. */
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

    /** Whether the plan date falls on or after the day paused and before the day resumed. */
    boolean skips(LocalDate date) {
        return !date.isBefore(pausedOn) && (resumedOn == null || date.isBefore(resumedOn));
    }

    boolean isOpen() {
        return resumedOn == null;
    }

    void resume(LocalDate on) {
        this.resumedOn = on;
    }
}
