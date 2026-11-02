package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A hold on a person, from its from-date (from the beginning of time when it has none) to its
 *  to-date (open-ended when it has none), both days included. */
@Embeddable
public class Hold {

    @Column(name = "from_date")
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

    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether the hold covers a day. */
    public boolean covers(LocalDate day) {
        return (fromDate == null || !day.isBefore(fromDate)) && (toDate == null || !day.isAfter(toDate));
    }
}
