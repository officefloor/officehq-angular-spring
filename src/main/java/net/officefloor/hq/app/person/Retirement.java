package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A past retirement of a person: from its date to the day they were unretired. */
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

    /** The day the person was unretired. */
    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether a date falls strictly between the retirement's from and to: no claim may be dated on it. */
    public boolean bars(LocalDate date) {
        return date.isAfter(fromDate) && date.isBefore(toDate);
    }
}
