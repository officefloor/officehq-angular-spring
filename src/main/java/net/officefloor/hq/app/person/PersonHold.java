package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A hold put on a person: from its from-date to its to-date, both included (no to-date: it does
 *  not end). Their OPEN claims dated within it, unless bound by a statement, count in no live total. */
@Entity
@Table(name = "person_hold")
public class PersonHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date")
    private LocalDate to;

    /** The sheet's today when the hold was put on; null for one put on before it was kept. */
    @Column(name = "made_on", updatable = false)
    private LocalDate madeOn;

    protected PersonHold() {
    }

    public PersonHold(String sheetId, Long personId, LocalDate from, LocalDate to, LocalDate madeOn) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.from = from;
        this.to = to;
        this.madeOn = madeOn;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getPersonId() {
        return personId;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** Whether the hold stood at the end of a day: it was put on while the sheet's today was on or
     *  before it (one put on before that was kept always stood). */
    public boolean madeAsOf(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }

    /** Whether a date is within the hold: on or after its from-date and on or before its to-date. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && (to == null || !date.isAfter(to));
    }
}
