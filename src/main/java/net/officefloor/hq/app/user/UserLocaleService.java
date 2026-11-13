package net.officefloor.hq.app.user;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each user's locale. Choosing one writes no audit record. */
@Service
public class UserLocaleService {

    /** The locales a user may choose; the first is every user's until they choose another. */
    public static final List<String> LOCALES = List.of("en", "de");

    private final UserLocaleRepository locales;

    public UserLocaleService(UserLocaleRepository locales) {
        this.locales = locales;
    }

    /** The user's locale: en unless they chose another. */
    @Transactional(readOnly = true)
    public String locale(String user) {
        return locales.findById(user).map(UserLocale::getLocale).orElse(LOCALES.get(0));
    }

    /** Choose the user's locale; one not in {@link #LOCALES} is refused. */
    @Transactional
    public String choose(String user, String locale) {
        String l = locale == null ? "" : locale.trim();
        if (!LOCALES.contains(l)) {
            throw new LocaleRefusedException("Choose a locale: " + String.join(" or ", LOCALES) + ".");
        }
        UserLocale row = locales.findById(user).orElseGet(() -> new UserLocale(user, l));
        row.setLocale(l);
        locales.save(row);
        return l;
    }
}
