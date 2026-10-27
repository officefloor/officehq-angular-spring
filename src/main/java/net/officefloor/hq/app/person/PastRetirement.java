package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A retirement a person was unretired from: from its from-date to the sheet's today when they were unretired. */
@Embeddable
public class PastRetirement {

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    /** The sheet's today when the retirement date was set; null: before that was kept. */
    @Column(name = "retired_on")
    private LocalDate retiredOn;

    /** The sheet's today when the person was unretired. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected PastRetirement() {
    }

    public PastRetirement(LocalDate from, LocalDate to, LocalDate retiredOn, LocalDate madeOn) {
        this.from = from;
        this.to = to;
        this.retiredOn = retiredOn;
        this.madeOn = madeOn;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    public LocalDate getRetiredOn() {
        return retiredOn;
    }

    /** Whether the retirement had been set by the end of the day: set while the sheet's today was on or before it. */
    public boolean retiredBy(LocalDate day) {
        return retiredOn == null || !retiredOn.isAfter(day);
    }

    /** Whether the person had been unretired by the end of the day. */
    public boolean unretiredBy(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }

    /** Whether the date falls strictly after the from-date and before the to-date. */
    public boolean refuses(LocalDate date) {
        return date != null && date.isAfter(from) && date.isBefore(to);
    }
}
