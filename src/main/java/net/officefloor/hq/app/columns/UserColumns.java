package net.officefloor.hq.app.columns;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The people-list columns a user name has switched off on their screen. */
@Entity
@Table(name = "user_columns")
public class UserColumns {

    @Id
    @Column(name = "user_name", nullable = false)
    private String userName;

    /** The switched-off column keys, joined by commas; empty when every column shows. */
    @Column(name = "hidden", nullable = false)
    private String hidden;

    protected UserColumns() {
    }

    public UserColumns(String userName, String hidden) {
        this.userName = userName;
        this.hidden = hidden;
    }

    public String getUserName() {
        return userName;
    }

    public String getHidden() {
        return hidden;
    }

    public void setHidden(String hidden) {
        this.hidden = hidden;
    }
}
