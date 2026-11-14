package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A patron a person named: another person on the same sheet. */
@Entity
@Table(name = "person_patron")
public class PersonPatron {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "patron_id", nullable = false)
    private Long patronId;

    /** The sheet's today when the patron was named; null for one named before it was kept. */
    @Column(name = "made_on", updatable = false)
    private LocalDate madeOn;

    protected PersonPatron() {
    }

    public PersonPatron(String sheetId, Long personId, Long patronId, LocalDate madeOn) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.patronId = patronId;
        this.madeOn = madeOn;
    }

    /** Whether the patron was named at the end of a day: named while the sheet's today was on or
     *  before it (one named before that was kept always was). */
    public boolean madeAsOf(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
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

    /** Name another person as the patron instead. */
    public void setPatronId(Long patronId) {
        this.patronId = patronId;
    }

    public Long getPatronId() {
        return patronId;
    }
}
