package net.officefloor.hq.app.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's settings: its roles, its clerk limit, its floor and its terms. The signed-in user's name travels in the
 * {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class SettingsController {

    /** A user name and the role it has on the sheet (clerk or steward). */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole role) {
            return new RoleView(role.getUser(), role.getRole().name().toLowerCase());
        }
    }

    /** The sheet's settings; the floor is sent as text with two decimals; the clerk limit is sent as text with two decimals, null when there is none; the terms in days. */
    public record SettingsView(String clerkLimit, String floor, int terms, List<RoleView> roles) {
    }

    /** A role: clerk or steward. */
    public record RoleRequest(@NotNull @Pattern(regexp = "\\s*(clerk|steward)\\s*") String role) {
        Role roleValue() {
            return Role.valueOf(role.trim().toUpperCase());
        }
    }

    /** A clerk limit: 0.00 or more, with up to two decimals; blank or null for no limit. */
    public record ClerkLimitRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String value) {
        BigDecimal limitValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** A floor: 0.00 or more, with up to two decimals. */
    public record FloorRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String value) {
        BigDecimal floorValue() {
            return new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** Terms: a whole number of days, 0 or more. */
    public record TermsRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,4}\\s*") String value) {
        int termsValue() {
            return Integer.parseInt(value.trim());
        }
    }

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public SettingsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        String sheet = sheetId.trim();
        return new SettingsView(text(service.clerkLimit(sheet)), text(service.floor(sheet)), service.terms(sheet),
                service.roles(sheet).stream().map(RoleView::of).toList());
    }

    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClerkLimitRequest request) {
        String sheet = sheetId.trim();
        service.setClerkLimit(sheet, user.trim(), request.limitValue());
        return get(sheet, user);
    }

    @PutMapping("/settings/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FloorRequest request) {
        String sheet = sheetId.trim();
        service.setFloor(sheet, user.trim(), request.floorValue());
        return get(sheet, user);
    }

    @PutMapping("/settings/terms")
    public SettingsView setTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TermsRequest request) {
        String sheet = sheetId.trim();
        service.setTerms(sheet, user.trim(), request.termsValue());
        return get(sheet, user);
    }

    @PutMapping("/roles/{name}")
    public RoleView setRole(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RoleRequest request) {
        return RoleView.of(service.setRole(sheetId.trim(), user.trim(), name.trim(), request.roleValue()));
    }

    private static String text(BigDecimal figure) {
        return figure == null ? null : figure.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
