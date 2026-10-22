package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.SignedIn;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets")
@Validated
public class SheetController {

    /** What the screen is told about a loaded sheet. */
    public record SheetView(String id, LocalDate today) {

        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday());
        }
    }

    /** A change to the sheet's today. */
    public record TodayChange(@NotNull LocalDate today) {
    }

    /** What the screen is told about the sheet's settings: the clerk limit with two decimals, null for none. */
    public record SettingsView(String clerkLimit) {

        static SettingsView of(Sheet sheet) {
            return new SettingsView(sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString());
        }
    }

    /** A setting's value as entered: up to two decimals, or blank (or null) for none. */
    public record SettingChange(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value) {
    }

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    /** Load a sheet by id, starting an empty one if the id was never used. */
    @PostMapping("/{id}/load")
    public SheetView load(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id) {
        return SheetView.of(service.load(id));
    }

    /** Change the sheet's today. */
    @PutMapping("/{id}/today")
    public SheetView setToday(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid TodayChange change) {
        return SheetView.of(service.setToday(id, SignedIn.name(user), change.today()));
    }

    /** The sheet's settings. */
    @GetMapping("/{id}/settings")
    public SettingsView settings(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id) {
        return SettingsView.of(service.load(id));
    }

    /** Change the sheet's clerk limit; a blank value means no limit. */
    @PutMapping("/{id}/settings/clerk-limit")
    public SettingsView setClerkLimit(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid SettingChange change) {
        String value = change.value() == null ? "" : change.value().trim();
        return SettingsView.of(service.setClerkLimit(id, SignedIn.name(user),
                value.isEmpty() ? null : new BigDecimal(value)));
    }
}
