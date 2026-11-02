package net.officefloor.hq.app.watch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A user watching a person on a sheet: they are told when the person's claims are settled. */
@Entity
@Table(name = "person_watch")
public class Watch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "user_name", nullable = false)
    private String user;

    /** The sheet's today when the user started watching; null for one started before it was kept. */
    @Column(name = "started_on")
    private LocalDate startedOn;

    protected Watch() {
    }

    public Watch(String sheetId, Long personId, String user, LocalDate startedOn) {
        this.startedOn = startedOn;
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

    public LocalDate getStartedOn() {
        return startedOn;
    }
}
