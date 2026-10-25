package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Objects;

/** A time a plan was paused: the day it was paused and the day it was resumed (null while still paused). */
@Embeddable
public class PlanPause {

    @Column(name = "paused_on", nullable = false)
    private LocalDate pausedOn;

    @Column(name = "resumed_on")
    private LocalDate resumedOn;

    protected PlanPause() {
    }

    public PlanPause(LocalDate pausedOn, LocalDate resumedOn) {
        this.pausedOn = pausedOn;
        this.resumedOn = resumedOn;
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

    /** Whether the plan date {@code on} falls on or after the pause and before the resume. */
    boolean skips(LocalDate on) {
        return !on.isBefore(pausedOn) && (resumedOn == null || on.isBefore(resumedOn));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlanPause other && Objects.equals(pausedOn, other.pausedOn)
                && Objects.equals(resumedOn, other.resumedOn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pausedOn, resumedOn);
    }
}
