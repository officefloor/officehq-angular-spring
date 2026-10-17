package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A hold on a person from its from-date to its to-date (both included), or on with no to-date. */
@Embeddable
public class PersonHold {

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date")
    private LocalDate to;

    protected PersonHold() {
    }

    public PersonHold(LocalDate from, LocalDate to) {
        this.from = from;
        this.to = to;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** Whether a date is within the hold. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(from) && (to == null || !date.isAfter(to));
    }
}
