package net.officefloor.hq.app.locale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's locale on a sheet. The signed-in user's name travels in the {@code X-User} header.
 * Choosing it is not a change to the sheet: it goes through on an archived sheet, as of a date and for a
 * viewer (see the guards' exclusions). */
@RestController
@RequestMapping("/api/sheets/{sheetId}/locale")
@Validated
public class LocaleController {

    /** A user's locale. */
    public record LocaleView(String locale) {
    }

    /** A locale asked. */
    public record LocaleRequest(@NotNull @Size(max = 10) String locale) {
    }

    private final LocaleService service;

    public LocaleController(LocaleService service) {
        this.service = service;
    }

    @GetMapping
    public LocaleView get(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new LocaleView(service.localeOf(sheetId.trim(), user.trim()));
    }

    @PutMapping
    public LocaleView choose(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid LocaleRequest request) {
        return new LocaleView(service.choose(sheetId.trim(), user.trim(), request.locale().trim()));
    }
}
