package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A line of a claim's hold history: the claim held, or released, and the sheet's today then. */
@Embeddable
public class ClaimHold {

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

    @Column(name = "held", nullable = false)
    private boolean held;

    protected ClaimHold() {
    }

    ClaimHold(LocalDate on, boolean held) {
        this.on = on;
        this.held = held;
    }

    /** Whether the line was made while the sheet's today was on or before the date. */
    boolean madeBy(LocalDate date) {
        return !on.isAfter(date);
    }

    public LocalDate getOn() {
        return on;
    }

    public boolean isHeld() {
        return held;
    }
}
