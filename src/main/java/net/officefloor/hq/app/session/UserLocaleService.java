package net.officefloor.hq.app.session;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and saves each user's locale. Choosing a locale writes no audit line. */
@Service
public class UserLocaleService {

    private final UserLocaleRepository locales;

    public UserLocaleService(UserLocaleRepository locales) {
        this.locales = locales;
    }

    @Transactional(readOnly = true)
    public String localeOf(String user) {
        return locales.findById(user).map(UserLocale::getLocale).orElse(UserLocale.DEFAULT);
    }

    @Transactional
    public String setLocale(String user, String locale) {
        locales.findById(user).ifPresentOrElse(
                l -> l.setLocale(locale),
                () -> locales.save(new UserLocale(user, locale)));
        return locale;
    }
}
