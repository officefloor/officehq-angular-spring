package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The role a user name has on a sheet; a name with none is a clerk. A viewer sees everything and changes nothing. */
@Entity
@Table(name = "sheet_role")
public class SheetRole {

    public static final String CLERK = "clerk";
    public static final String STEWARD = "steward";
    public static final String VIEWER = "viewer";

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

    public SheetRole(String sheetId, String user, String role) {
        this.sheetId = sheetId;
        this.user = user;
        this.role = role;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
