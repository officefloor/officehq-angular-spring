package net.officefloor.hq.app.locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The locale a user name has chosen: how the screen shows them figures. */
@Entity
@Table(name = "user_locale")
public class UserLocale {

    /** The locale of a user who has chosen none. */
    public static final String DEFAULT = "en";

    @Id
    @Column(name = "user_name", nullable = false)
    private String userName;

    /** en or de. */
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
