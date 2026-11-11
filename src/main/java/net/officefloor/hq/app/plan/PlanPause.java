package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A pause of a plan: the day it was paused, and the day it was resumed (null while still paused). */
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

    boolean isOpen() {
        return resumedOn == null;
    }

    void resume(LocalDate on) {
        resumedOn = on;
    }

    /** Whether a plan date falls in this pause: on or after the paused day and before the resumed day. */
    boolean skips(LocalDate date) {
        return !date.isBefore(pausedOn) && (resumedOn == null || date.isBefore(resumedOn));
    }
}
