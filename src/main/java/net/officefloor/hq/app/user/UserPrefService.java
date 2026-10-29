package net.officefloor.hq.app.user;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Each user's locale: how the screen shows them figures. Not audited. */
@Service
public class UserPrefService {

    /** The locales a user may choose; the first is everyone's until they choose another. */
    public static final List<String> LOCALES = List.of("en", "de");

    private final UserPrefRepository prefs;

    public UserPrefService(UserPrefRepository prefs) {
        this.prefs = prefs;
    }

    @Transactional(readOnly = true)
    public String locale(String user) {
        return prefs.findById(user).map(UserPref::getLocale).orElse(LOCALES.get(0));
    }

    @Transactional
    public String setLocale(String user, String locale) {
        if (!LOCALES.contains(locale)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such locale.");
        }
        UserPref pref = prefs.findById(user).orElseGet(() -> new UserPref(user, locale));
        pref.setLocale(locale);
        prefs.save(pref);
        return locale;
    }
}
