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

    protected PersonHold() {
    }

    PersonHold(LocalDate from, LocalDate to) {
        this.from = from;
        this.to = to;
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
