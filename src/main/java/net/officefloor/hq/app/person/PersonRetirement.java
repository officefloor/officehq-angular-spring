package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A past retirement of a person, from its from-date to the sheet's today when they were unretired. */
@Entity
@Table(name = "person_retirement")
public class PersonRetirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    protected PersonRetirement() {
    }

    PersonRetirement(LocalDate fromDate, LocalDate toDate) {
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

    /** Whether a claim dated on the date is refused: it is after the from-date and before the to-date. */
    public boolean bars(LocalDate date) {
        return date.isAfter(fromDate) && date.isBefore(toDate);
    }
}
