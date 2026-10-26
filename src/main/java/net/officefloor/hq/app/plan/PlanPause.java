package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** One pause of a plan: the day it was paused and the day it was resumed, null while still paused. The
 *  plan dates on or after the first and before the second are skipped. */
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
        resumedOn = on;
    }

    boolean skips(LocalDate date) {
        return !date.isBefore(pausedOn) && (resumedOn == null || date.isBefore(resumedOn));
    }
}
