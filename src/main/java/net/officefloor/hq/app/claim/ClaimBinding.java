package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A line of a claim's binding history: a statement binding it to a figure (null: n/a), or it losing its
 *  binding, and the sheet's today then (null: before this was kept). */
@Embeddable
public class ClaimBinding {

    @Column(name = "on_date")
    private LocalDate on;

    @Column(name = "stated", nullable = false)
    private boolean stated;

    @Column(name = "stated_due", precision = 12, scale = 2)
    private BigDecimal statedDue;

    protected ClaimBinding() {
    }

    ClaimBinding(LocalDate on, boolean stated, BigDecimal statedDue) {
        this.on = on;
        this.stated = stated;
        this.statedDue = statedDue;
    }

    /** Whether the line was made while the sheet's today was on or before the date. */
    boolean madeBy(LocalDate date) {
        return on == null || !on.isAfter(date);
    }

    public LocalDate getOn() {
        return on;
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }
}
