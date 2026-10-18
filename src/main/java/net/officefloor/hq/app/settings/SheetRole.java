package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The role a sheet gives a user name. */
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

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    protected SheetRole() {}

    public SheetRole(String sheetId, String userName, Role role) {
        this.sheetId = sheetId;
        this.userName = userName;
        this.role = role;
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public String getUserName() { return userName; }

    public Role getRole() { return role; }

    public void setRole(Role role) { this.role = role; }
}
