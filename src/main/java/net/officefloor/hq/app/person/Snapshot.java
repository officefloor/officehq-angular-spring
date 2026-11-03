package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A snapshot of a person: the day being left when it was written, and their due-total on it
 *  (text with two decimals, or "n/a"). */
@Embeddable
public class Snapshot {

    @Column(name = "left_on", nullable = false)
    private LocalDate day;

    @Column(name = "due_total", nullable = false)
    private String dueTotal;

    protected Snapshot() {
    }

    public Snapshot(LocalDate day, String dueTotal) {
        this.day = day;
        this.dueTotal = dueTotal;
    }

    public LocalDate getDay() {
        return day;
    }

    public String getDueTotal() {
        return dueTotal;
    }
}
