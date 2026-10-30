package net.officefloor.hq.app.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in by name; there is no password. The front end sends the name back as {@code X-User}. Signing in
 * answers with the user's saved locale, which they may change.
 */
@RestController
@Validated
public class SessionController {

    public record SignIn(@NotBlank @Size(max = 100) String name) {
    }

    public record SignedIn(String name, String locale) {
    }

    public record LocaleForm(@NotBlank @Pattern(regexp = "en|de") String locale) {
    }

    public record LocaleView(String locale) {
    }

    private final UserLocaleService locales;

    public SessionController(UserLocaleService locales) {
        this.locales = locales;
    }

    @PostMapping("/api/signin")
    public SignedIn signIn(@Valid @RequestBody SignIn request) {
        String name = request.name().trim();
        return new SignedIn(name, locales.localeOf(name));
    }

    @PutMapping("/api/me/locale")
    public LocaleView setLocale(
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody LocaleForm form) {
        return new LocaleView(locales.setLocale(user.trim(), form.locale()));
    }
}
