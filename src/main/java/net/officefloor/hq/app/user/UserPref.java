package net.officefloor.hq.app.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user's own preferences, kept across every sheet. */
@Entity
@Table(name = "user_pref")
public class UserPref {

    @Id
    @Column(name = "user_name", nullable = false)
    private String user;

    /** How the screen shows this user figures: en (1234.56) or de (1.234,56). */
    @Column(name = "locale", nullable = false)
    private String locale;

    protected UserPref() {
    }

    public UserPref(String user, String locale) {
        this.user = user;
        this.locale = locale;
    }

    public String getUser() {
        return user;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }
}
