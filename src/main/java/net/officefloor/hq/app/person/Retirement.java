package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A past retirement of a person: from the day they were retired from to the day they were
 *  unretired. */
@Embeddable
public class Retirement {

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    protected Retirement() {
    }

    public Retirement(LocalDate fromDate, LocalDate toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether a day falls after the from-date and before the to-date. */
    public boolean within(LocalDate day) {
        return day.isAfter(fromDate) && day.isBefore(toDate);
    }
}
