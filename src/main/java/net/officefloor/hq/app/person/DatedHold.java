package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** One of a person's holds: from its from-date to its to-date (none: open-ended), both inclusive. */
@Embeddable
public class DatedHold {

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    protected DatedHold() {
    }

    public DatedHold(LocalDate fromDate, LocalDate toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether the day lies within the hold. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(fromDate) && (toDate == null || !date.isAfter(toDate));
    }
}
