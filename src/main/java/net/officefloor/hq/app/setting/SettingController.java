package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class SettingController {

    /** The sheet's settings as the screen shows them; the clerk limit has two decimals, null when none. */
    public record SettingsView(String clerkLimit, List<RoleView> roles) {}

    /** A user name's role, as clerk or steward. */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole r) {
            return new RoleView(r.getUserName(), r.getRole().label());
        }
    }

    /** A clerk limit with at most two decimals; blank or null: no limit. */
    public record ClerkLimitRequest(@Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String value) {

        BigDecimal limitValue() {
            return value == null || value.isEmpty() ? null : new BigDecimal(value).setScale(2);
        }
    }

    /** A role: clerk or steward. */
    public record RoleRequest(@NotNull @Pattern(regexp = "clerk|steward") String role) {

        Role roleValue() {
            return Role.valueOf(role.toUpperCase());
        }
    }

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public SettingsView get(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new SettingsView(text(service.clerkLimit(sheetId)),
                service.roles(sheetId).stream().map(RoleView::of).toList());
    }

    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClerkLimitRequest request) {
        service.setClerkLimit(sheetId, user, request.limitValue());
        return get(sheetId, user);
    }

    @PutMapping("/roles/{name}")
    public RoleView setRole(
            @PathVariable @NotBlank @Size(max = 100) String name,
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RoleRequest request) {
        return RoleView.of(service.setRole(sheetId, user, name, request.roleValue()));
    }

    private static String text(BigDecimal limit) {
        return limit == null ? null : limit.setScale(2).toPlainString();
    }
}
