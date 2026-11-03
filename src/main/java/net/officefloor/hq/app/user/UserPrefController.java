package net.officefloor.hq.app.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own locale, kept per user across every sheet. */
@RestController
@RequestMapping("/api/me/locale")
@Validated
public class UserPrefController {

    public record LocaleChange(@NotBlank @Size(max = 10) String locale) {
    }

    public record LocaleView(String locale) {
    }

    private final UserPrefService service;

    public UserPrefController(UserPrefService service) {
        this.service = service;
    }

    @GetMapping
    public LocaleView get(@RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new LocaleView(service.localeOf(user.trim()));
    }

    @PutMapping
    public LocaleView set(@RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody LocaleChange request) {
        return new LocaleView(service.setLocale(user.trim(), request.locale().trim()));
    }
}
