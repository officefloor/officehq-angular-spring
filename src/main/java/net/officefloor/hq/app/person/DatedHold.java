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

    /** The sheet's today when it was recorded; none: from the beginning of time. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected DatedHold() {
    }

    public DatedHold(LocalDate fromDate, LocalDate toDate, LocalDate madeOn) {
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

    /** Whether the day lies within the hold. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(fromDate) && (toDate == null || !date.isAfter(toDate));
    }

    /** Whether it was recorded by the end of a day: the sheet's today then on or before it. */
    public boolean madeBy(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }
}
