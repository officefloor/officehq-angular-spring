package net.officefloor.hq.app.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A user's own preferences: read, with en when none is saved, and saved. Not audited. */
@Service
public class UserPrefService {

    /** The locale a user has until they choose one. */
    public static final String DEFAULT_LOCALE = "en";

    private final UserPrefRepository prefs;

    public UserPrefService(UserPrefRepository prefs) {
        this.prefs = prefs;
    }

    /** The user's locale; en when they have not chosen one. */
    @Transactional(readOnly = true)
    public String locale(String user) {
        return prefs.findById(user).map(UserPref::getLocale).orElse(DEFAULT_LOCALE);
    }

    /** Save the user's locale. */
    @Transactional
    public String setLocale(String user, String locale) {
        UserPref pref = prefs.findById(user).orElseGet(() -> new UserPref(user, locale));
        pref.setLocale(locale);
        return prefs.save(pref).getLocale();
    }
}
