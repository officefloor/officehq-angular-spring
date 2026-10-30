package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A past retirement of a person: from the date they were retired from to the day they were unretired. */
@Embeddable
public class RetirementSpan {

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    protected RetirementSpan() {
    }

    public RetirementSpan(LocalDate fromDate, LocalDate toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether the date is after its from-date and before its to-date: a claim then is refused. */
    public boolean bars(LocalDate date) {
        return date.isAfter(fromDate) && date.isBefore(toDate);
    }
}
