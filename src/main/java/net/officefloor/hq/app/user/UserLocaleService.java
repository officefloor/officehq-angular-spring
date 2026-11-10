package net.officefloor.hq.app.user;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Each user's locale: how the screen shows them figures ({@code en}, the default, or {@code de}).
 * Only the display follows it; entries, exports and audit lines do not, so saving it is not audited.
 */
@Service
public class UserLocaleService {

    public static final String DEFAULT = "en";

    private static final Set<String> LOCALES = Set.of("en", "de");

    private final UserLocaleRepository locales;

    public UserLocaleService(UserLocaleRepository locales) {
        this.locales = locales;
    }

    @Transactional(readOnly = true)
    public String localeOf(String user) {
        return locales.findById(user).map(UserLocale::getLocale).orElse(DEFAULT);
    }

    @Transactional
    public String setLocale(String user, String locale) {
        if (!LOCALES.contains(locale)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a locale: " + locale);
        }
        locales.findById(user).ifPresentOrElse(l -> l.setLocale(locale),
                () -> locales.save(new UserLocale(user, locale)));
        return locale;
    }
}
