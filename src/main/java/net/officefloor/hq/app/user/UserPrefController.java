package net.officefloor.hq.app.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

/** The signed-in user's own locale. */
@RestController
public class UserPrefController {

    public record LocaleForm(@NotBlank String locale) {
    }

    public record LocaleView(String locale) {
    }

    private final UserPrefService prefs;

    public UserPrefController(UserPrefService prefs) {
        this.prefs = prefs;
    }

    @GetMapping("/api/me/locale")
    public LocaleView locale(@SessionAttribute(SessionController.USER) String user) {
        return new LocaleView(prefs.locale(user));
    }

    @PutMapping("/api/me/locale")
    public LocaleView setLocale(@Valid @RequestBody LocaleForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return new LocaleView(prefs.setLocale(user, form.locale()));
    }
}
