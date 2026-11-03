package net.officefloor.hq.app.user;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A user's locale, en unless chosen, and the people-list columns their screen leaves out. Choosing
 *  either writes no audit line. */
@Service
public class UserPrefService {

    public static final String DEFAULT_LOCALE = "en";

    public static final Set<String> LOCALES = Set.of("en", "de");

    /** The people-list columns a user may switch off, in the order the chooser lists them. */
    public static final List<String> COLUMNS = List.of("claimed", "due-total", "excess", "statement-count",
            "stated-total", "statement-date", "overdue-count", "overdue-total", "patrons", "backing", "hold",
            "retired", "terms", "contact", "aliases");

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

    @Transactional(readOnly = true)
    public List<String> hiddenColumnsOf(String user) {
        return prefs.findById(user).map(p -> split(p.getHiddenColumns())).orElse(List.of());
    }

    @Transactional
    public List<String> setHiddenColumns(String user, List<String> hidden) {
        if (!COLUMNS.containsAll(hidden)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown column");
        }
        List<String> ordered = COLUMNS.stream().filter(hidden::contains).toList();
        String joined = String.join(",", ordered);
        prefs.findById(user).ifPresentOrElse(p -> p.setHiddenColumns(joined), () -> {
            UserPref pref = new UserPref(user, DEFAULT_LOCALE);
            pref.setHiddenColumns(joined);
            prefs.save(pref);
        });
        return ordered;
    }

    private static List<String> split(String joined) {
        return joined.isBlank() ? List.of() : Arrays.asList(joined.split(","));
    }
}
