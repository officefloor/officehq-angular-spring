package net.officefloor.hq.app.locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Each user's locale; a user who never chose one is en. Choosing one writes no audit line. */
@Service
public class UserLocaleService {

    /** The locale of a user who never chose one. */
    public static final String DEFAULT = "en";

    private final UserLocaleRepository repository;

    public UserLocaleService(UserLocaleRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public String of(String user) {
        return repository.findById(user).map(UserLocale::getLocale).orElse(DEFAULT);
    }

    @Transactional
    public String set(String user, String locale) {
        UserLocale chosen = repository.findById(user).orElseGet(() -> new UserLocale(user, locale));
        chosen.setLocale(locale);
        repository.save(chosen);
        return locale;
    }
}
