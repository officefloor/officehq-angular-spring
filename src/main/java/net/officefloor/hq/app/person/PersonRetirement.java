package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A past retirement of a person: from its from-date to the sheet's today the person was unretired on. */
@Entity
@Table(name = "person_retirement")
public class PersonRetirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    /** The sheet's today when the person was retired; null when retired before this was kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected PersonRetirement() {
    }

    PersonRetirement(LocalDate from, LocalDate to, LocalDate madeOn) {
        this.from = from;
        this.to = to;
        this.madeOn = madeOn;
    }

    Long getId() {
        return id;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** Whether a claim of this date is refused by it: dated after its from-date and before its to-date. */
    boolean refuses(LocalDate date) {
        return date.isAfter(from) && date.isBefore(to);
    }

    /** Whether, at the end of a date standing in for the sheet's today, the retirement was made, not yet
     *  ended and reached. */
    boolean inForceAsOf(LocalDate asOf) {
        return (madeOn == null || !madeOn.isAfter(asOf)) && asOf.isBefore(to) && !asOf.isBefore(from);
    }
}
