package net.officefloor.hq.app.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in by name only (no password): the name is checked and echoed back trimmed, with the user's
 * locale (en unless they have chosen one). The locale is saved per user, across sheets, and not audited.
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    static final String DEFAULT_LOCALE = "en";

    public record SignIn(@NotBlank @Size(max = 100) String name) {}

    public record LocaleChoice(@NotBlank @Pattern(regexp = "en|de") String locale) {}

    public record User(String name, String locale) {}

    private final UserLocaleRepository locales;

    public SessionController(UserLocaleRepository locales) {
        this.locales = locales;
    }

    @PostMapping
    @Transactional(readOnly = true)
    public User signIn(@Valid @RequestBody SignIn request) {
        String name = request.name().trim();
        return new User(name, localeOf(name));
    }

    @PutMapping("/locale")
    @Transactional
    public User setLocale(@RequestHeader("X-User") String user, @Valid @RequestBody LocaleChoice request) {
        String name = user.trim();
        UserLocale row = locales.findById(name).orElseGet(() -> new UserLocale(name, request.locale()));
        row.setLocale(request.locale());
        locales.save(row);
        return new User(name, row.getLocale());
    }

    private String localeOf(String name) {
        return locales.findById(name).map(UserLocale::getLocale).orElse(DEFAULT_LOCALE);
    }
}
