package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/**
 * One of a person's past retirements: from its from-date to the sheet's today when they were
 * unretired. No claim of theirs can be dated after the from-date and before the to-date.
 */
@Embeddable
public class PastRetirement {

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    /** The sheet's today when the retirement was recorded; none: from the beginning of time. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected PastRetirement() {
    }

    public PastRetirement(LocalDate fromDate, LocalDate toDate, LocalDate madeOn) {
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.madeOn = madeOn;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether a date lies strictly after the from-date and before the to-date. */
    public boolean excludes(LocalDate date) {
        return date.isAfter(fromDate) && date.isBefore(toDate);
    }

    /**
     * Whether the person stood retired at the end of a day by it: recorded by then, the day reached
     * its from-date and they were not yet unretired (the to-date is the day they were unretired).
     */
    public boolean retiredAsOf(LocalDate day) {
        return (madeOn == null || !madeOn.isAfter(day)) && !day.isBefore(fromDate) && day.isBefore(toDate);
    }
}
