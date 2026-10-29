package net.officefloor.hq.app.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user's own preferences, kept across sheets. */
@Entity
@Table(name = "user_pref")
public class UserPref {

    @Id
    @Column(name = "user_name")
    private String userName;

    @Column(name = "locale", nullable = false)
    private String locale;

    @Column(name = "hidden_columns", nullable = false)
    private String hiddenColumns = "";

    protected UserPref() {
    }

    public UserPref(String userName, String locale) {
        this.userName = userName;
        this.locale = locale;
    }

    public String getUserName() {
        return userName;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getHiddenColumns() {
        return hiddenColumns;
    }

    public void setHiddenColumns(String hiddenColumns) {
        this.hiddenColumns = hiddenColumns;
    }
}
