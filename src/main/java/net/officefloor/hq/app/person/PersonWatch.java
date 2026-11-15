package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import net.officefloor.hq.app.history.HistoryListener;

/** A user watching a person on a sheet: settling, approving or re-settling the person's claims
 *  notifies them. */
@Entity
@EntityListeners(HistoryListener.class)
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
    private String userName;

    protected PersonWatch() {
    }

    public PersonWatch(String sheetId, Long personId, String userName) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.userName = userName;
    }

    public Long getId() {
        return id;
    }

    public Long getPersonId() {
        return personId;
    }

    public String getUserName() {
        return userName;
    }

    public String getSheetId() {
        return sheetId;
    }
}
