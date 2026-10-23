package net.officefloor.hq.app.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user's own preferences, kept per user name across every sheet: the locale figures are shown in and
 * the people-list columns switched off on their screen.
 */
@Entity
@Table(name = "user_pref")
public class UserPref {

    @Id
    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "locale", nullable = false)
    private String locale;

    /** The switched-off columns, separated by commas; empty when every column is shown. */
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
