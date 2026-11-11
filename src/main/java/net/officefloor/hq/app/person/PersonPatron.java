package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Another person on the sheet a person has named as their patron. */
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

    protected PersonPatron() {
    }

    public PersonPatron(String sheetId, Long personId, Long patronId) {
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

    /** Make the patron another person's (a merge). */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    /** Name another person as the patron instead (a merge). */
    public void setPatronId(Long patronId) {
        this.patronId = patronId;
    }
}
