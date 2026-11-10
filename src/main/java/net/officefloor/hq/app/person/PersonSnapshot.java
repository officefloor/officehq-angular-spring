package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One of a person's snapshot lines: the day being left when the sheet's today moved, and their due-total then. */
@Embeddable
public class PersonSnapshot {

    /** The order the lines were written in, oldest first. */
    @Column(name = "pos", nullable = false)
    private int pos;

    @Column(name = "left_on", nullable = false)
    private LocalDate day;

    /** The due-total on the day; null for n/a. */
    @Column(name = "due_total")
    private BigDecimal dueTotal;

    protected PersonSnapshot() {
    }

    public LocalDate getDay() {
        return day;
    }

    public BigDecimal getDueTotal() {
        return dueTotal;
    }
}
