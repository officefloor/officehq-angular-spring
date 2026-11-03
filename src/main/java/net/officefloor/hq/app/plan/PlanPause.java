package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A time a plan was paused: from the day it was paused up to, not including, the day it was
 *  resumed (null while it is still paused). */
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

    boolean isOpen() {
        return resumedOn == null;
    }

    void resume(LocalDate on) {
        this.resumedOn = on;
    }

    /** Whether the date falls on or after the day it was paused and before the day it was resumed. */
    boolean covers(LocalDate date) {
        return !date.isBefore(pausedOn) && (resumedOn == null || date.isBefore(resumedOn));
    }
}
