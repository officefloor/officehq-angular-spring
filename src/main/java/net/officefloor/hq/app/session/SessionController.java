package net.officefloor.hq.app.session;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in by name only; there is no password. The name is kept in the HTTP session as {@link #USER}. */
@RestController
public class SessionController {

    /** The session attribute holding the signed-in user's name. */
    public static final String USER = "user";

    public record SignIn(@NotBlank @Size(max = 100) String name) {
    }

    public record User(String name) {
    }

    @PostMapping("/api/signin")
    public User signIn(@Valid @RequestBody SignIn request, HttpSession session) {
        String name = request.name().trim();
        session.setAttribute(USER, name);
        return new User(name);
    }
}
