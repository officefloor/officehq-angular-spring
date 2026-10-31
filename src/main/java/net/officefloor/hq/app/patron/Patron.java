package net.officefloor.hq.app.patron;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A person's patron: another person on the same sheet that the person names. */
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

    protected Patron() {
    }

    public Patron(String sheetId, Long personId, Long patronId) {
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

    public Long getPatronId() {
        return patronId;
    }
}
