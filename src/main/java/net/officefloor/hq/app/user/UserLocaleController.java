package net.officefloor.hq.app.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's locale. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/user/locale")
public class UserLocaleController {

    public record LocaleView(String locale) {
    }

    public record LocaleRequest(@Size(max = 10) String locale) {
    }

    private final UserLocaleService service;

    public UserLocaleController(UserLocaleService service) {
        this.service = service;
    }

    /** The user's locale: en unless they chose another. */
    @GetMapping
    public LocaleView get(@RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return new LocaleView(service.locale(user.trim()));
    }

    /** Choose the user's locale. */
    @PutMapping
    public LocaleView choose(@RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid LocaleRequest request) {
        return new LocaleView(service.choose(user.trim(), request.locale()));
    }

    /** A locale that cannot be chosen is refused with the reason, for the screen to show. */
    @ExceptionHandler(LocaleRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refused(LocaleRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
