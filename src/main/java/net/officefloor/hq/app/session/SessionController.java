package net.officefloor.hq.app.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in by name: there is no password, the name is checked and echoed back trimmed, with the user's locale.
 * The signed-in user's locale is read and chosen here too; it is kept per user and is not audited.
 * So are the people-list columns the user has switched off on their screen.
 */
@RestController
@Validated
@RequestMapping("/api/session")
public class SessionController {

    public record SignIn(@NotBlank @Size(max = 100) String name) {
    }

    public record User(String name, String locale, List<String> hiddenColumns) {
    }

    public record LocaleForm(@NotNull @Pattern(regexp = "en|de") String locale) {
    }

    public record LocaleView(String locale) {
    }

    public record ColumnsForm(@NotNull List<@NotNull @Pattern(regexp = COLUMN_PATTERN) String> hidden) {
    }

    public record ColumnsView(List<String> hidden) {
    }

    private static final String COLUMN_PATTERN = "claimed|due-total|statement-count|stated-total|statement-date"
            + "|overdue-count|overdue-total|patrons|backing|hold|retired|terms|contact|aliases|excess";

    private final UserLocaleRepository locales;
    private final UserColumnsRepository columns;

    public SessionController(UserLocaleRepository locales, UserColumnsRepository columns) {
        this.locales = locales;
        this.columns = columns;
    }

    @PostMapping
    @Transactional(readOnly = true)
    public User signIn(@Valid @RequestBody SignIn request) {
        String name = request.name().trim();
        return new User(name, localeOf(name), hiddenOf(name));
    }

    @GetMapping("/locale")
    @Transactional(readOnly = true)
    public LocaleView locale(@RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new LocaleView(localeOf(user.trim()));
    }

    @PutMapping("/locale")
    @Transactional
    public LocaleView setLocale(
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody LocaleForm request) {
        String name = user.trim();
        UserLocale chosen = locales.findById(name).orElseGet(() -> new UserLocale(name, request.locale()));
        chosen.setLocale(request.locale());
        locales.save(chosen);
        return new LocaleView(chosen.getLocale());
    }

    @PutMapping("/columns")
    @Transactional
    public ColumnsView setColumns(
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ColumnsForm request) {
        String name = user.trim();
        UserColumns chosen = columns.findById(name).orElseGet(() -> new UserColumns(name));
        chosen.setHidden(request.hidden());
        columns.save(chosen);
        return new ColumnsView(chosen.getHidden());
    }

    private List<String> hiddenOf(String user) {
        return columns.findById(user).map(UserColumns::getHidden).orElse(List.of());
    }

    private String localeOf(String user) {
        return locales.findById(user).map(UserLocale::getLocale).orElse(UserLocale.DEFAULT);
    }
}
