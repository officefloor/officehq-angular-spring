package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The role a user name has been given on a sheet. */
@Entity
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

    protected SheetRole() {}

    public SheetRole(String sheetId, String user, Role role) {
        this.sheetId = sheetId;
        this.user = user;
        this.role = role.code();
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getUser() {
        return user;
    }

    public Role getRole() {
        return Role.of(role).orElse(Role.CLERK);
    }

    public void setRole(Role role) {
        this.role = role.code();
    }
}
