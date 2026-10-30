package net.officefloor.hq.app.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user's locale: how the screen shows them figures. It is kept per user, across every sheet. */
@Entity
@Table(name = "user_locale")
public class UserLocale {

    /** The locale a user has until they choose one. */
    public static final String DEFAULT = "en";

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
