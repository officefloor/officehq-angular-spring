package net.officefloor.hq.app.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in by name; there is no password. The front end keeps the signed-in name and sends it on
 * every request in the {@value #USER_HEADER} header.
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    public static final String USER_HEADER = "X-User";

    public record SignIn(@NotBlank @Size(max = 100) String name) {}

    public record Session(String name) {}

    @PostMapping
    public Session signIn(@Valid @RequestBody SignIn request) {
        return new Session(request.name().trim());
    }
}
