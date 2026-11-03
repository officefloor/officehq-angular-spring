package net.officefloor.hq.app.user;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A user's locale: en unless chosen. Choosing one writes no audit line. */
@Service
public class UserPrefService {

    public static final String DEFAULT_LOCALE = "en";

    public static final Set<String> LOCALES = Set.of("en", "de");

    private final UserPrefRepository prefs;

    public UserPrefService(UserPrefRepository prefs) {
        this.prefs = prefs;
    }

    @Transactional(readOnly = true)
    public String localeOf(String user) {
        return prefs.findById(user).map(UserPref::getLocale).orElse(DEFAULT_LOCALE);
    }

    @Transactional
    public String setLocale(String user, String locale) {
        if (!LOCALES.contains(locale)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose en or de");
        }
        prefs.findById(user).ifPresentOrElse(p -> p.setLocale(locale), () -> prefs.save(new UserPref(user, locale)));
        return locale;
    }
}
