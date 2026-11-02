package net.officefloor.hq.app.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    public record SignedIn(String name) {
    }

    @PostMapping
    public SignedIn signIn(@Valid @RequestBody SignIn request) {
        return new SignedIn(request.name().trim());
    }
}
