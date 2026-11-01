package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A SNAPSHOT line written for a person: the day being left and their due-total on it (null: n/a). */
@Embeddable
public class PersonSnapshot {

    @Column(name = "on_date", nullable = false)
    private LocalDate day;

    @Column(name = "due_total", precision = 14, scale = 2)
    private BigDecimal dueTotal;

    protected PersonSnapshot() {}

    public PersonSnapshot(LocalDate day, BigDecimal dueTotal) {
        this.day = day;
        this.dueTotal = dueTotal;
    }

    public LocalDate getDay() { return day; }

    public BigDecimal getDueTotal() { return dueTotal; }
}
