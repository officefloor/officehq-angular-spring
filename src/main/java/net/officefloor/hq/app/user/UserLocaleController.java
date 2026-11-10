package net.officefloor.hq.app.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Saving the signed-in user's locale (it is read back at sign-in). */
@RestController
@RequestMapping("/api/users")
public class UserLocaleController {

    public record SetLocaleRequest(@NotBlank @Size(max = 100) String user, @NotBlank @Size(max = 10) String locale) {
    }

    public record LocaleResponse(String locale) {
    }

    private final UserLocaleService service;

    public UserLocaleController(UserLocaleService service) {
        this.service = service;
    }

    @PostMapping("/locale")
    public LocaleResponse setLocale(@Valid @RequestBody SetLocaleRequest req) {
        return new LocaleResponse(service.setLocale(req.user().trim(), req.locale().trim()));
    }
}
