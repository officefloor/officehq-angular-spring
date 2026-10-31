package net.officefloor.hq.app.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user's chosen locale, which decides how the screen shows them figures. A user without one is en. */
@Entity
@Table(name = "user_locale")
public class UserLocale {

    /** The locale a user has until they choose another. */
    public static final String DEFAULT = "en";

    @Id
    @Column(name = "user_name", nullable = false, length = 100)
    private String userName;

    @Column(name = "locale", nullable = false, length = 10)
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
