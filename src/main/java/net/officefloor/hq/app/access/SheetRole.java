package net.officefloor.hq.app.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The role assigned to a user name on a sheet; a name with none is a clerk. */
@Entity
@Table(name = "sheet_role")
public class SheetRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "role", nullable = false)
    private String role;

    protected SheetRole() {
    }

    public SheetRole(String sheetId, String userName, Role role) {
        this.sheetId = sheetId;
        this.userName = userName;
        this.role = role.key();
    }

    public String getUserName() {
        return userName;
    }

    public Role getRole() {
        return Role.of(role).orElse(Role.CLERK);
    }

    public void setRole(Role role) {
        this.role = role.key();
    }
}
