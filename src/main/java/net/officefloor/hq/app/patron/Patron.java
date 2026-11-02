package net.officefloor.hq.app.patron;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A patron of a person: another person on the same sheet they have named. */
@Entity
@Table(name = "patron")
public class Patron {

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
    @Column(name = "named_on")
    private LocalDate namedOn;

    protected Patron() {
    }

    public Patron(String sheetId, Long personId, Long patronId, LocalDate namedOn) {
        this.namedOn = namedOn;
        this.sheetId = sheetId;
        this.personId = personId;
        this.patronId = patronId;
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

    public LocalDate getNamedOn() {
        return namedOn;
    }

    public Long getPatronId() {
        return patronId;
    }

    /** Name another person as the patron instead (the one named was merged into them). */
    public void setPatronId(Long patronId) {
        this.patronId = patronId;
    }
}
