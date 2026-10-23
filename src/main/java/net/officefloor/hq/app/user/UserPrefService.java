package net.officefloor.hq.app.user;

import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A user's own preferences: read, with en and every column shown when none is saved, and saved. Not audited. */
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

    /** The people-list columns the user has switched off; none when they have not chosen. */
    @Transactional(readOnly = true)
    public List<String> hiddenColumns(String user) {
        return prefs.findById(user).map(UserPrefService::split).orElse(List.of());
    }

    /** Save the people-list columns the user has switched off. */
    @Transactional
    public List<String> setHiddenColumns(String user, List<String> columns) {
        UserPref pref = prefs.findById(user).orElseGet(() -> new UserPref(user, DEFAULT_LOCALE));
        pref.setHiddenColumns(String.join(",", columns.stream().distinct().sorted().toList()));
        return split(prefs.save(pref));
    }

    private static List<String> split(UserPref pref) {
        return Arrays.stream(pref.getHiddenColumns().split(",")).filter(c -> !c.isEmpty()).toList();
    }
}
