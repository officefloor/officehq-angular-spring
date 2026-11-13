package net.officefloor.hq.app.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user's chosen locale, kept per user across every sheet. */
@Entity
@Table(name = "user_locale")
public class UserLocale {

    @Id
    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "locale", nullable = false)
    private String locale;

    protected UserLocale() {
    }

    public UserLocale(String userName, String locale) {
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
}
