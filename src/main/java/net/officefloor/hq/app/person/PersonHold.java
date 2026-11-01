package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A hold on a person from its from-date (null: the beginning of time) to its to-date (null: open-ended), both included. */
@Embeddable
public class PersonHold {

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    protected PersonHold() {}

    public PersonHold(LocalDate fromDate, LocalDate toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public LocalDate getFromDate() { return fromDate; }

    public LocalDate getToDate() { return toDate; }

    /** Whether the date falls within the hold. */
    public boolean contains(LocalDate date) {
        return (fromDate == null || !date.isBefore(fromDate)) && (toDate == null || !date.isAfter(toDate));
    }
}
