package net.officefloor.hq.app.locale;

import java.util.Set;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The locale each user has chosen on a sheet: how the screen shows figures to them. Writes no audit. */
@Service
public class LocaleService {

    /** The locale a user has until they choose another. */
    public static final String DEFAULT = "en";

    /** The locales a user may choose. */
    public static final Set<String> LOCALES = Set.of("en", "de");

    private final SheetRepository sheets;
    private final UserLocaleRepository locales;

    public LocaleService(SheetRepository sheets, UserLocaleRepository locales) {
        this.sheets = sheets;
        this.locales = locales;
    }

    @Transactional(readOnly = true)
    public String localeOf(String sheetId, String user) {
        return locales.findBySheetIdAndUser(sheetId, user).map(UserLocale::getLocale).orElse(DEFAULT);
    }

    @Transactional
    public String choose(String sheetId, String user, String locale) {
        if (!LOCALES.contains(locale)) throw new LocaleUnknownException();
        if (!sheets.existsById(sheetId)) throw new SheetNotFoundException();
        locales.findBySheetIdAndUser(sheetId, user).ifPresentOrElse(l -> l.setLocale(locale),
                () -> locales.save(new UserLocale(sheetId, user, locale)));
        return locale;
    }
}
