package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

/** The role a sheet assigns one user name. */
@Entity
@Table(name = "sheet_role")
@IdClass(SheetRole.Key.class)
public class SheetRole {

    public record Key(String sheetId, String userName) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "sheet_id")
    private String sheetId;

    @Id
    @Column(name = "user_name")
    private String userName;

    @Column(name = "role", nullable = false)
    private String role;

    protected SheetRole() {
    }

    public SheetRole(String sheetId, String userName, Role role) {
        this.sheetId = sheetId;
        this.userName = userName;
        setRole(role);
    }

    public String getUserName() {
        return userName;
    }

    public Role getRole() {
        return Role.of(role).orElse(Role.CLERK);
    }

    public void setRole(Role role) {
        this.role = role.label();
    }
}
