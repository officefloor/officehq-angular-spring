package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A past retirement of a person: from its from-date to the sheet's today they were unretired on. */
@Embeddable
public class PersonRetirement {

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    /** The sheet's today when it was set; null when set before this was kept. */
    @Column(name = "set_on")
    private LocalDate setOn;

    protected PersonRetirement() {}

    public PersonRetirement(LocalDate fromDate, LocalDate toDate, LocalDate setOn) {
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.setOn = setOn;
    }

    public LocalDate getFromDate() { return fromDate; }

    public LocalDate getToDate() { return toDate; }

    /** Whether a claim may not be dated on the date: after the from-date and before the to-date. */
    public boolean bars(LocalDate date) { return date.isAfter(fromDate) && date.isBefore(toDate); }

    /**
     * Whether, as it stood at the end of the date, they were retired by it: set by then, from on or before
     * it, and not yet ended.
     */
    public boolean retiredAt(LocalDate date) {
        return (setOn == null || !setOn.isAfter(date)) && !date.isBefore(fromDate) && toDate.isAfter(date);
    }
}
