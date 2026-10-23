package net.officefloor.hq.app.user;

import java.util.List;
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

    /** What the screen is told about the people-list columns the user has switched off. */
    public record ColumnsView(List<String> hidden) {
    }

    /** The people-list columns to switch off, each a column name such as due-total. */
    public record ColumnsForm(
            @NotNull @Size(max = 50) List<@NotNull @Pattern(regexp = "[a-z][a-z-]{0,39}") String> hidden) {
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

    /** The people-list columns the signed-in user has switched off; none when they have not chosen. */
    @GetMapping("/columns")
    public ColumnsView columns(@RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return new ColumnsView(service.hiddenColumns(SignedIn.name(user)));
    }

    /** Save the people-list columns the signed-in user has switched off. */
    @PutMapping("/columns")
    public ColumnsView setColumns(@RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ColumnsForm form) {
        return new ColumnsView(service.setHiddenColumns(SignedIn.name(user), form.hidden()));
    }
}
