package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import net.officefloor.hq.app.history.HistoryListener;

/** The role a user name holds on a sheet. */
@Entity
@EntityListeners(HistoryListener.class)
@Table(name = "sheet_role")
public class SheetRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "user_name", nullable = false)
    private String user;

    @Column(name = "role", nullable = false)
    private String role;

    protected SheetRole() {
    }

    public SheetRole(String sheetId, String user, Role role) {
        this.sheetId = sheetId;
        this.user = user;
        this.role = role.key();
    }

    public String getUser() {
        return user;
    }

    public Role getRole() {
        return Role.of(role);
    }

    public void setRole(Role role) {
        this.role = role.key();
    }

    public String getSheetId() {
        return sheetId;
    }
}
