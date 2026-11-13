package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The roles a sheet's permission matrix lets take an action: a comma separated list of role labels. */
@Entity
@Table(name = "sheet_permission")
public class SheetPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    /** The action: open, settle, void, reassign, close or reopen. */
    @Column(name = "action", nullable = false)
    private String action;

    /** The roles, comma separated: clerk, steward or clerk,steward. */
    @Column(name = "roles", nullable = false)
    private String roles;

    protected SheetPermission() {
    }

    public SheetPermission(String sheetId, String action, String roles) {
        this.sheetId = sheetId;
        this.action = action;
        this.roles = roles;
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
