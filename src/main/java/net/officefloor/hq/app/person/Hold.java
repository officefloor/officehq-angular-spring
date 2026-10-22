package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A hold on a person: from a date, to a date (inclusive) or with no end when it has none. */
@Embeddable
public class Hold {

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    protected Hold() {
    }

    public Hold(LocalDate fromDate, LocalDate toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    /** The last date the hold covers; null when it has no end. */
    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether a date falls within the hold, both ends included. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(fromDate) && (toDate == null || !date.isAfter(toDate));
    }
}
