package net.officefloor.hq.app.role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The role a user name has on a sheet; a name with none is a clerk. */
@Entity
@Table(name = "sheet_role")
public class SheetRole {

    /** The role every name has until it is given another. */
    public static final String CLERK = "clerk";
    /** A steward may settle a claim of any due. */
    public static final String STEWARD = "steward";

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

    public SheetRole(String sheetId, String userName, String role) {
        this.sheetId = sheetId;
        this.userName = userName;
        this.role = role;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getUserName() {
        return userName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
