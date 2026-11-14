package net.officefloor.hq.app.locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's locale: kept per user, not per sheet, and not audited. */
@RestController
@Validated
@RequestMapping("/api/me/locale")
public class UserLocaleController {

    /** The locale every user has until they choose another. */
    static final String DEFAULT = "en";

    public record LocaleView(String locale) {
    }

    public record LocaleRequest(@NotBlank @Pattern(regexp = "en|de") String locale) {
    }

    private final UserLocaleRepository repository;

    public UserLocaleController(UserLocaleRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public LocaleView get(@RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return new LocaleView(repository.findById(SignedInUser.decode(user))
                .map(UserLocale::getLocale).orElse(DEFAULT));
    }

    @PutMapping
    @Transactional
    public LocaleView set(@RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated LocaleRequest request) {
        String name = SignedInUser.decode(user);
        UserLocale chosen = repository.findById(name).orElseGet(() -> new UserLocale(name, request.locale()));
        chosen.setLocale(request.locale());
        return new LocaleView(repository.save(chosen).getLocale());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
