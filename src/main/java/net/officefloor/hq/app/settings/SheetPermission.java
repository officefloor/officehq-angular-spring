package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The roles that may take an action on a sheet, as a comma separated list of lower-case roles; blank
 * for the action's standing rule. */
@Entity
@Table(name = "sheet_permission")
public class SheetPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "perm_action", nullable = false)
    private Action action;

    @Column(name = "roles", nullable = false)
    private String roles;

    protected SheetPermission() {
    }

    public SheetPermission(String sheetId, Action action, String roles) {
        this.sheetId = sheetId;
        this.action = action;
        this.roles = roles;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Action getAction() {
        return action;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }
}
