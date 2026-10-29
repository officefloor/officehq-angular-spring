package net.officefloor.hq.app.user;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Each user's locale, how the screen shows them figures, and the people-list columns their screen leaves out. Not audited. */
@Service
public class UserPrefService {

    /** The locales a user may choose; the first is everyone's until they choose another. */
    public static final List<String> LOCALES = List.of("en", "de");

    /** The people-list columns a user may switch off; every one is shown until they switch it off. */
    public static final List<String> COLUMNS = List.of("claimed", "due-total", "statement-count", "stated-total",
            "statement-date", "overdue-count", "overdue-total", "patrons", "backing", "excess", "hold", "retired",
            "terms", "contact", "aliases");

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

    @Transactional(readOnly = true)
    public List<String> hiddenColumns(String user) {
        return prefs.findById(user).map(p -> split(p.getHiddenColumns())).orElse(List.of());
    }

    @Transactional
    public List<String> setHiddenColumns(String user, List<String> hidden) {
        if (!COLUMNS.containsAll(hidden)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such column.");
        }
        List<String> kept = COLUMNS.stream().filter(hidden::contains).toList();
        UserPref pref = prefs.findById(user).orElseGet(() -> new UserPref(user, LOCALES.get(0)));
        pref.setHiddenColumns(String.join(",", kept));
        prefs.save(pref);
        return kept;
    }

    private static List<String> split(String hidden) {
        return hidden.isEmpty() ? List.of() : List.of(hidden.split(","));
    }
}
