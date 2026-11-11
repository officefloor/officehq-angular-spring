package net.officefloor.hq.app.locale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's locale. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/me/locale")
@Validated
public class UserLocaleController {

    /** A user's locale: en or de. */
    public record LocaleView(String locale) {
    }

    /** The locale to choose: en or de. */
    public record LocaleRequest(@NotNull @Pattern(regexp = "en|de") String locale) {
    }

    private final UserLocaleService service;

    public UserLocaleController(UserLocaleService service) {
        this.service = service;
    }

    @GetMapping
    public LocaleView get(@RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new LocaleView(service.localeOf(user.trim()));
    }

    @PutMapping
    public LocaleView choose(@RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid LocaleRequest request) {
        return new LocaleView(service.choose(user.trim(), request.locale()));
    }
}
