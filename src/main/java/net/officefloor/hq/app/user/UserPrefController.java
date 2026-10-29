package net.officefloor.hq.app.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

/** The signed-in user's own locale and the people-list columns their screen leaves out. */
@RestController
public class UserPrefController {

    public record LocaleForm(@NotBlank String locale) {
    }

    public record LocaleView(String locale) {
    }

    public record ColumnsForm(@NotNull List<String> hidden) {
    }

    public record ColumnsView(List<String> hidden) {
    }

    private final UserPrefService prefs;

    public UserPrefController(UserPrefService prefs) {
        this.prefs = prefs;
    }

    @GetMapping("/api/me/locale")
    public LocaleView locale(@SessionAttribute(SessionController.USER) String user) {
        return new LocaleView(prefs.locale(user));
    }

    @PutMapping("/api/me/locale")
    public LocaleView setLocale(@Valid @RequestBody LocaleForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return new LocaleView(prefs.setLocale(user, form.locale()));
    }

    @GetMapping("/api/me/columns")
    public ColumnsView columns(@SessionAttribute(SessionController.USER) String user) {
        return new ColumnsView(prefs.hiddenColumns(user));
    }

    @PutMapping("/api/me/columns")
    public ColumnsView setColumns(@Valid @RequestBody ColumnsForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return new ColumnsView(prefs.setHiddenColumns(user, form.hidden()));
    }
}
