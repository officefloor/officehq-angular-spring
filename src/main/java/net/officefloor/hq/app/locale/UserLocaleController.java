package net.officefloor.hq.app.locale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own locale. */
@RestController
@RequestMapping("/api/me/locale")
@Validated
public class UserLocaleController {

    /** A user's locale: en or de. */
    public record LocaleView(@NotNull @Pattern(regexp = "en|de") String locale) {
    }

    private final UserLocaleService service;

    public UserLocaleController(UserLocaleService service) {
        this.service = service;
    }

    @GetMapping
    public LocaleView get(@RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return new LocaleView(service.of(SignedInUser.name(user)));
    }

    @PutMapping
    public LocaleView set(@RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Valid LocaleView form) {
        return new LocaleView(service.set(SignedInUser.name(user), form.locale()));
    }
}
