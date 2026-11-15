package net.officefloor.hq.app.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's locale (en unless chosen): kept per user, across sheets, and written to no
 *  audit record. It changes only how the screen shows figures. */
@RestController
@RequestMapping("/api/users/locale")
public class UserLocaleController {

    /** The locale a user has before choosing one. */
    public static final String DEFAULT = "en";

    public record LocaleBody(@NotNull @Pattern(regexp = "en|de") String locale) {
    }

    private final UserLocaleRepository locales;

    public UserLocaleController(UserLocaleRepository locales) {
        this.locales = locales;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public LocaleBody get(@RequestHeader(CurrentUser.HEADER) String user) {
        return new LocaleBody(locales.findById(user.trim()).map(UserLocale::getLocale).orElse(DEFAULT));
    }

    @PutMapping
    @Transactional
    public LocaleBody set(@RequestHeader(CurrentUser.HEADER) String user, @Valid @RequestBody LocaleBody body) {
        String name = user.trim();
        UserLocale saved = locales.findById(name).orElseGet(() -> new UserLocale(name, body.locale()));
        saved.setLocale(body.locale());
        locales.save(saved);
        return body;
    }
}
