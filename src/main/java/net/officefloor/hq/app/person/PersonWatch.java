package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user watching a person: settling, approving or re-settling the person's claims notifies them. */
@Entity
@Table(name = "person_watch")
public class PersonWatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "user_name", nullable = false)
    private String user;

    protected PersonWatch() {
    }

    public PersonWatch(String sheetId, Long personId, String user) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.user = user;
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

    public String getUser() {
        return user;
    }
}
