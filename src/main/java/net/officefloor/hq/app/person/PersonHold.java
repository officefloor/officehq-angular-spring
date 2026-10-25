package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A hold on a person, from its from-date (or the beginning of time, when it has none) to its to-date
 *  (or for good, when it has none), both days included. */
@Entity
@Table(name = "person_hold")
public class PersonHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_date")
    private LocalDate from;

    @Column(name = "to_date")
    private LocalDate to;

    /** The sheet's today when the hold was put on; null when put on before this was kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected PersonHold() {
    }

    PersonHold(LocalDate from, LocalDate to, LocalDate madeOn) {
        this.from = from;
        this.to = to;
        this.madeOn = madeOn;
    }

    /** Whether the hold was put on while the sheet's today was on or before the date. */
    boolean madeBy(LocalDate date) {
        return madeOn == null || !madeOn.isAfter(date);
    }

    Long getId() {
        return id;
    }

    /** The date the hold starts, or null when it holds from the beginning of time. */
    public LocalDate getFrom() {
        return from;
    }

    /** The date the hold ends, or null when it has no end. */
    public LocalDate getTo() {
        return to;
    }

    /** Whether the date falls within the hold. */
    boolean covers(LocalDate date) {
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }
}
