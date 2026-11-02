package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** The roles that may take an action on a sheet, as a comma separated list; an action with none
 *  recorded keeps the standing rule. */
@Entity
@Table(name = "sheet_permission")
@IdClass(SheetPermission.Key.class)
public class SheetPermission {

    /** The key of a permission: its sheet and action. */
    public static class Key implements Serializable {
        private String sheetId;
        private String action;

        protected Key() {
        }

        public Key(String sheetId, String action) {
            this.sheetId = sheetId;
            this.action = action;
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
    @Column(name = "sheet_id")
    private String sheetId;

    @Id
    @Column(name = "action")
    private String action;

    @Column(name = "roles", nullable = false)
    private String roles;

    protected SheetPermission() {
    }

    public SheetPermission(String sheetId, String action, String roles) {
        this.sheetId = sheetId;
        this.action = action;
        this.roles = roles;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getAction() {
        return action;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }
}
