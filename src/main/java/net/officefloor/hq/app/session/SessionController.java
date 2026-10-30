package net.officefloor.hq.app.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in by name; there is no password. The front end sends the name back as {@code X-User}. Signing in
 * answers with the user's saved locale, which they may change. Each user also chooses which columns of the
 * people list their screen shows.
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

    /** Show (true) or switch off (false) a column. */
    public record ColumnForm(@NotNull Boolean shown) {
    }

    private final UserLocaleService locales;
    private final HiddenColumnService columns;

    public SessionController(UserLocaleService locales, HiddenColumnService columns) {
        this.locales = locales;
        this.columns = columns;
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

    /** The columns of the people list the user has switched off. */
    @GetMapping("/api/me/hidden-columns")
    public List<String> hiddenColumns(@RequestHeader("X-User") @NotBlank String user) {
        return columns.hiddenOf(user.trim());
    }

    /** Show or switch off one column; answers with every column the user has switched off now. */
    @PutMapping("/api/me/columns/{column}")
    public List<String> setColumn(@RequestHeader("X-User") @NotBlank String user,
            @PathVariable @Pattern(regexp = HiddenColumn.KEYS) String column, @Valid @RequestBody ColumnForm form) {
        return columns.setShown(user.trim(), column, form.shown());
    }
}
