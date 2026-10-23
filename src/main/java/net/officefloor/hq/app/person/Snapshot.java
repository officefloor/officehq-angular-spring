package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A person's due-total as it stood on a day the sheet's today moved on from (null: n/a). */
@Embeddable
public class Snapshot {

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Column(name = "due_total", precision = 14, scale = 2)
    private BigDecimal dueTotal;

    protected Snapshot() {
    }

    public Snapshot(LocalDate asOf, BigDecimal dueTotal) {
        this.asOf = asOf;
        this.dueTotal = dueTotal;
    }

    /** The day being left. */
    public LocalDate getAsOf() {
        return asOf;
    }

    /** The due-total on that day; null when n/a. */
    public BigDecimal getDueTotal() {
        return dueTotal;
    }
}
