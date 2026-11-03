package net.officefloor.hq.app.role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A role given to a user name on a sheet, recorded on the sheet's today then: the role the user had
 *  at the end of a day is the last one recorded on or before it. */
@Entity
@Table(name = "role_revision")
public class RoleRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "role", nullable = false)
    private String role;

    /** The sheet's today when the role was given. */
    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    protected RoleRevision() {
    }

    public RoleRevision(String sheetId, String userName, String role, LocalDate recordedOn) {
        this.sheetId = sheetId;
        this.userName = userName;
        this.role = role;
        this.recordedOn = recordedOn;
    }

    public String getRole() {
        return role;
    }

    public LocalDate getRecordedOn() {
        return recordedOn;
    }
}
