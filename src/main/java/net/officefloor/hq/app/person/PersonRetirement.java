package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A past retirement of a person: from the date they were retired from to the day they were unretired. */
@Entity
@Table(name = "person_retirement")
public class PersonRetirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The date they were retired from. */
    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    /** The day they were unretired (the sheet's today then). */
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

    /** Whether a claim on a date is refused by it: the date is after its from and before its to. */
    public boolean refuses(LocalDate date) {
        return date.isAfter(fromDate) && date.isBefore(toDate);
    }
}
