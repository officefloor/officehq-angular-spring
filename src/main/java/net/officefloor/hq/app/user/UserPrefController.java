package net.officefloor.hq.app.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.officefloor.hq.app.SignedIn;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own preferences, the same on every sheet. */
@RestController
@RequestMapping("/api/user")
@Validated
public class UserPrefController {

    /** What the screen is told about the user's locale. */
    public record LocaleView(String locale) {
    }

    /** A locale as chosen on the screen: en or de. */
    public record LocaleForm(@NotNull @Pattern(regexp = "en|de") String locale) {
    }

    private final UserPrefService service;

    public UserPrefController(UserPrefService service) {
        this.service = service;
    }

    /** The signed-in user's locale; en when they have not chosen one. */
    @GetMapping("/locale")
    public LocaleView locale(@RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return new LocaleView(service.locale(SignedIn.name(user)));
    }

    /** Save the signed-in user's locale. */
    @PutMapping("/locale")
    public LocaleView setLocale(@RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid LocaleForm form) {
        return new LocaleView(service.setLocale(SignedIn.name(user), form.locale()));
    }
}
