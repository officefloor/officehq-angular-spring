package net.officefloor.hq.app.columns;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The people-list columns a user name has switched off on a sheet. */
@Entity
@Table(name = "user_columns")
public class UserColumns {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "user_name", nullable = false)
    private String user;

    /** The columns switched off, comma separated; empty when every column shows. */
    @Column(name = "hidden", nullable = false)
    private String hidden;

    protected UserColumns() {
    }

    public UserColumns(String sheetId, String user, String hidden) {
        this.sheetId = sheetId;
        this.user = user;
        this.hidden = hidden;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getUser() {
        return user;
    }

    public String getHidden() {
        return hidden;
    }

    public void setHidden(String hidden) {
        this.hidden = hidden;
    }
}
