package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A hold on a person, from a date (or from the beginning of time when it has none) to a date (or with
 *  no end when it has none), both inclusive. */
@Entity
@Table(name = "person_hold")
public class PersonHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The first day the hold covers; none when it covers from the beginning of time. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    /** The last day the hold covers; none while it has no end. */
    @Column(name = "to_date")
    private LocalDate toDate;

    protected PersonHold() {
    }

    PersonHold(LocalDate fromDate, LocalDate toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
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

    /** Whether the hold covers a date. */
    public boolean covers(LocalDate date) {
        return (fromDate == null || !date.isBefore(fromDate)) && (toDate == null || !date.isAfter(toDate));
    }
}
