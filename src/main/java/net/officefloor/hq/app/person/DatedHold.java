package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A hold on a person from its from-date to its to-date, both included (a null to-date: with no end). */
@Embeddable
public class DatedHold {

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date")
    private LocalDate to;

    protected DatedHold() {
    }

    public DatedHold(LocalDate from, LocalDate to) {
        this.from = from;
        this.to = to;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** Whether the date falls within the hold. */
    public boolean covers(LocalDate date) {
        return date != null && !date.isBefore(from) && (to == null || !date.isAfter(to));
    }
}
