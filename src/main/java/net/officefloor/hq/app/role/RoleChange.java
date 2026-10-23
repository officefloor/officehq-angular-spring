package net.officefloor.hq.app.role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A setting of a user name's role on a sheet, on the sheet's today it was made on (null for one made
 * before these were kept, in force on every date), so the roles can be read as they stood at a date.
 */
@Entity
@Table(name = "sheet_role_history")
public class RoleChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "set_on")
    private LocalDate setOn;

    protected RoleChange() {
    }

    public RoleChange(String sheetId, String userName, String role, LocalDate setOn) {
        this.sheetId = sheetId;
        this.userName = userName;
        this.role = role;
        this.setOn = setOn;
    }

    public String getUserName() {
        return userName;
    }

    public String getRole() {
        return role;
    }

    public LocalDate getSetOn() {
        return setOn;
    }
}
