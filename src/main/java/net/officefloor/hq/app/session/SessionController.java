package net.officefloor.hq.app.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import net.officefloor.hq.app.user.UserPrefService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in by name only: there is no password. */
@RestController
@RequestMapping("/api/signin")
public class SessionController {

    public record SignIn(@NotBlank @Size(max = 100) String name) {
    }

    /** The name signed in with, and the locale the screen shows them figures in. */
    public record SignedIn(String name, String locale) {
    }

    private final UserPrefService prefs;

    public SessionController(UserPrefService prefs) {
        this.prefs = prefs;
    }

    @PostMapping
    public SignedIn signIn(@Valid @RequestBody SignIn request) {
        String name = request.name().trim();
        return new SignedIn(name, prefs.localeOf(name));
    }
}
