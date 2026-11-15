package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** The roles that may take an action on a sheet, as its permission matrix sets them. */
@Entity
@Table(name = "sheet_permission")
@IdClass(SheetPermission.Key.class)
public class SheetPermission {

    /** A sheet's action: the matrix holds one entry for each. */
    public static class Key implements Serializable {
        private String sheetId;
        private String action;

        protected Key() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(sheetId, k.sheetId) && Objects.equals(action, k.action);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sheetId, action);
        }
    }

    @Id
    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Id
    @Column(name = "action", nullable = false)
    private String action;

    /** The roles, comma separated, as they were set. */
    @Column(name = "roles", nullable = false)
    private String roles;

    protected SheetPermission() {
    }

    public SheetPermission(String sheetId, Permission action, String roles) {
        this.sheetId = sheetId;
        this.action = action.key();
        this.roles = roles;
    }

    public Permission getAction() {
        return Permission.of(action);
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }

    /** The roles that may take the action. */
    public Set<Role> roleSet() {
        Set<Role> out = EnumSet.noneOf(Role.class);
        Arrays.stream(roles.split(",")).map(String::trim).filter(r -> !r.isEmpty()).map(Role::of).forEach(out::add);
        return out;
    }
}
