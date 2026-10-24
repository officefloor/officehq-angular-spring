package net.officefloor.hq.app.locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The locale a user name has chosen on a sheet. */
@Entity
@Table(name = "user_locale")
public class UserLocale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "user_name", nullable = false)
    private String user;

    @Column(name = "locale", nullable = false)
    private String locale;

    protected UserLocale() {
    }

    public UserLocale(String sheetId, String user, String locale) {
        this.sheetId = sheetId;
        this.user = user;
        this.locale = locale;
    }

    public String getSheetId() {
        return sheetId;
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
