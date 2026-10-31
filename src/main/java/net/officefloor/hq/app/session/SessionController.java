package net.officefloor.hq.app.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in by name: there is no password, the name is checked and echoed back trimmed. */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    public record SignIn(@NotBlank @Size(max = 100) String name) {
    }

    public record User(String name) {
    }

    @PostMapping
    public User signIn(@Valid @RequestBody SignIn request) {
        return new User(request.name().trim());
    }
}
