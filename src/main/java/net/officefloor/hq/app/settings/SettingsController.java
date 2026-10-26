package net.officefloor.hq.app.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class SettingsController {

    /**
     * The sheet's settings as the screen shows them; the clerk limit carries two decimals, or is null for none, and
     * the floor carries two decimals.
     */
    public record SettingsView(String clerkLimit, String floor) {
        static SettingsView of(Sheet sheet) {
            return new SettingsView(sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.getFloor().toPlainString());
        }
    }

    /** The clerk limit as the form submits it: 0.00 or more with up to two decimals, or null for no limit. */
    public record ClerkLimitForm(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value) {
    }

    /** The floor as the form submits it: 0.00 or more with up to two decimals. */
    public record FloorForm(@NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value) {
    }

    public record RoleView(String user, String role) {
        static RoleView of(SheetRole r) {
            return new RoleView(r.getUser(), r.getRole());
        }
    }

    public record RoleForm(@NotNull String role) {
    }

    private final SettingsService service;
    private final SheetService sheets;

    public SettingsController(SettingsService service, SheetService sheets) {
        this.service = service;
        this.sheets = sheets;
    }

    @GetMapping("/settings")
    public SettingsView settings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return SettingsView.of(sheets.load(sheetId));
    }

    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody ClerkLimitForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setClerkLimit(user, sheetId, form.value()));
    }

    @PutMapping("/settings/floor")
    public SettingsView setFloor(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody FloorForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setFloor(user, sheetId, form.value()));
    }

    /** The roles set on the sheet, by user name; a name not listed is a clerk. */
    @GetMapping("/roles")
    public List<RoleView> roles(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.roles(sheetId).stream().map(RoleView::of).toList();
    }

    @PutMapping("/roles/{name}")
    public RoleView setRole(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @Valid @RequestBody RoleForm form, @SessionAttribute(SessionController.USER) String user) {
        return RoleView.of(service.setRole(user, sheetId, name, form.role()));
    }
}
