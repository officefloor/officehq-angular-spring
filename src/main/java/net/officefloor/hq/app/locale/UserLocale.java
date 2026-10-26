package net.officefloor.hq.app.locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user's chosen locale: how the screen shows them figures. Kept per user, not per sheet. */
@Entity
@Table(name = "user_locale")
public class UserLocale {

    @Id
    @Column(name = "user_name", nullable = false)
    private String user;

    @Column(name = "locale", nullable = false)
    private String locale;

    protected UserLocale() {
    }

    public UserLocale(String user, String locale) {
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
