package net.officefloor.hq.app.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The locale a user has chosen for how the screen shows them figures; en when none is recorded. */
@Entity
@Table(name = "user_locale")
public class UserLocale {

    @Id
    @Column(name = "user_name", length = 100)
    private String userName;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    protected UserLocale() {}

    public UserLocale(String userName, String locale) {
        this.userName = userName;
        this.locale = locale;
    }

    public String getUserName() { return userName; }

    public String getLocale() { return locale; }

    public void setLocale(String locale) { this.locale = locale; }
}
