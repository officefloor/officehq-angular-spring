package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class SettingController {

    /** What the screen shows of the sheet's settings; the clerk limit has two decimals, or is null
     *  when there is no limit. */
    public record SettingsView(String clerkLimit, List<RoleView> roles) {
    }

    /** A role the sheet assigns a user name. */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole role) {
            return new RoleView(role.getUserName(), role.getRole().label());
        }
    }

    /** The clerk limit as entered; none means no limit. */
    public record ClerkLimitForm(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value) {
    }

    /** A role to assign a user name, as entered on the form. */
    public record RoleForm(@NotBlank @Size(max = 200) String user, @NotNull String role) {
    }

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public SettingsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String id = sheetId.trim();
        return view(service.clerkLimit(id), service.roles(id));
    }

    /** Set the sheet's clerk limit, or clear it. */
    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ClerkLimitForm form) {
        String id = sheetId.trim();
        BigDecimal limit = service.setClerkLimit(id, SignedInUser.name(user), form.value());
        return view(limit, service.roles(id));
    }

    /** Assign a user name a role on the sheet. */
    @PutMapping("/roles")
    public RoleView setRole(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid RoleForm form) {
        Role role = Role.of(form.role().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such role."));
        return RoleView.of(service.setRole(sheetId.trim(), SignedInUser.name(user), form.user().trim(), role));
    }

    private static SettingsView view(BigDecimal limit, List<SheetRole> roles) {
        return new SettingsView(limit == null ? null : limit.toPlainString(),
                roles.stream().map(RoleView::of).toList());
    }
}
