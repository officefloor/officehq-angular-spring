package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A sheet's settings: the role each user name has, and its clerk limit. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
public class SettingController {

    /** The role is clerk or steward. */
    public record RoleForm(@NotBlank @Size(max = 100) String user, @NotNull String role) {}

    public record RoleView(String user, String role) {

        static RoleView of(SheetRole role) {
            return new RoleView(role.getUser(), role.getRole().code());
        }
    }

    /** The clerk limit has up to two decimals, 0.00 or more; blank means no limit. */
    public record SettingsForm(@Pattern(regexp = "|\\d{1,10}(\\.\\d{1,2})?") String clerkLimit) {}

    /** The clerk limit is shown with two decimals; null when there is none. */
    public record SettingsView(String clerkLimit) {

        static SettingsView of(BigDecimal clerkLimit) {
            return new SettingsView(clerkLimit != null ? clerkLimit.setScale(2).toPlainString() : null);
        }
    }

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    public List<RoleView> roles(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return service.roles(sheetId).stream().map(RoleView::of).toList();
    }

    @PutMapping("/roles")
    public RoleView setRole(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody RoleForm form) {
        Role role = Role.of(form.role().trim()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "A role is clerk or steward."));
        return RoleView.of(service.setRole(user, sheetId, form.user().trim(), role));
    }

    @GetMapping("/settings")
    public SettingsView settings(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return SettingsView.of(service.clerkLimit(sheetId));
    }

    @PutMapping("/settings")
    public SettingsView save(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody SettingsForm form) {
        String limit = form.clerkLimit() == null ? "" : form.clerkLimit();
        return SettingsView.of(service.setClerkLimit(user, sheetId, limit.isEmpty() ? null : new BigDecimal(limit)));
    }
}
