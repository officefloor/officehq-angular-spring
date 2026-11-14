package net.officefloor.hq.app.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class SettingsController {

    /** The sheet's settings as the screen shows them: the clerk limit with two decimals, null for
     *  none, and the floor with two decimals. */
    public record SettingsView(String clerkLimit, String floor) {
        static SettingsView of(BigDecimal clerkLimit, BigDecimal floor) {
            return new SettingsView(clerkLimit == null ? null : clerkLimit.toPlainString(), floor.toPlainString());
        }
    }

    /** The clerk limit as the form submits it: 0.00 or more with up to two decimals, blank for no limit. */
    public record ClerkLimitForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value) {
    }

    /** The floor as the form submits it: 0.00 or more with up to two decimals, blank for the default. */
    public record FloorForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value) {
    }

    /** A user name's role on the sheet. */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole r) {
            return new RoleView(r.getUser(), r.getRole().key());
        }
    }

    /** A role as the form submits it: a user name without spaces and clerk or steward. */
    public record RoleForm(
            @NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String user,
            @NotNull @Pattern(regexp = "clerk|steward") String role) {
    }

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public SettingsView settings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(sheetId);
    }

    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClerkLimitForm body) {
        BigDecimal limit = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setClerkLimit(sheetId, user.trim(), limit);
        return view(sheetId);
    }

    @PutMapping("/settings/floor")
    public SettingsView setFloor(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FloorForm body) {
        BigDecimal floor = body.value().isEmpty() ? Sheet.DEFAULT_FLOOR : new BigDecimal(body.value());
        service.setFloor(sheetId, user.trim(), floor);
        return view(sheetId);
    }

    private SettingsView view(String sheetId) {
        return SettingsView.of(service.clerkLimit(sheetId), service.floor(sheetId));
    }

    @GetMapping("/roles")
    public List<RoleView> roles(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return service.roles(sheetId).stream().map(RoleView::of).toList();
    }

    @PutMapping("/roles")
    public RoleView setRole(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RoleForm body) {
        return RoleView.of(service.setRole(sheetId, user.trim(), body.user().trim(), Role.of(body.role())));
    }
}
