package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A hold of a person, from its from-date to its to-date, both inclusive (no to-date: it does not end). */
@Entity
@Table(name = "person_hold")
public class PersonHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    /** The last date the hold covers; null while it does not end. */
    @Column(name = "to_date")
    private LocalDate toDate;

    /** The sheet's today when the hold was added; null for a hold added before that was kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected PersonHold() {
    }

    PersonHold(LocalDate fromDate, LocalDate toDate, LocalDate madeOn) {
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.madeOn = madeOn;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    /** Whether the hold was added while the sheet's today was on or before the date. */
    public boolean madeBy(LocalDate date) {
        return madeOn == null || !madeOn.isAfter(date);
    }

    /** Whether the date is within the hold. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(fromDate) && (toDate == null || !date.isAfter(toDate));
    }
}
