package net.officefloor.hq.app.locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each user's locale. Choosing one writes no audit record. */
@Service
public class UserLocaleService {

    private final UserLocaleRepository locales;

    public UserLocaleService(UserLocaleRepository locales) {
        this.locales = locales;
    }

    /** The user's locale: en unless they chose another. */
    @Transactional(readOnly = true)
    public String localeOf(String user) {
        return locales.findById(user).map(UserLocale::getLocale).orElse(UserLocale.DEFAULT);
    }

    /** Save the user's locale. */
    @Transactional
    public String choose(String user, String locale) {
        UserLocale saved = locales.findById(user).orElseGet(() -> new UserLocale(user, locale));
        saved.setLocale(locale);
        return locales.save(saved).getLocale();
    }
}
