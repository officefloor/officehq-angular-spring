package net.officefloor.hq.app.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in by name only; there is no password. */
@RestController
public class SessionController {

    public record SignIn(@NotBlank @Size(max = 100) String name) {
    }

    public record User(String name) {
    }

    @PostMapping("/api/signin")
    public User signIn(@Valid @RequestBody SignIn request) {
        return new User(request.name().trim());
    }
}
