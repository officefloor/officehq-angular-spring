package net.officefloor.hq.app.access;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's roles and settings. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class AccessController {

    public record RoleView(String user, String role) {

        static RoleView of(SheetRole r) {
            return new RoleView(r.getUserName(), r.getRole().key());
        }
    }

    /**
     * The sheet's settings as shown; a limit is a figure with two decimals, null for none; so is the floor;
     * the terms are a number of days.
     */
    public record SettingsView(String clerkLimit, String floor, String terms) {

        static SettingsView of(BigDecimal limit, BigDecimal floor, int terms) {
            return new SettingsView(limit == null ? null : limit.toPlainString(), floor.toPlainString(),
                    Integer.toString(terms));
        }
    }

    public record AccessView(List<RoleView> roles, SettingsView settings) {
    }

    /** A user name and a role: clerk or steward. */
    public record RoleForm(@NotBlank @Size(max = 200) String user, @NotNull @Pattern(regexp = "clerk|steward") String role) {
    }

    /**
     * The settings to save; one left out (null) is kept as it is. A clerk limit is 0.00 or more with up
     * to two decimals, blank meaning no limit; so is a floor, blank putting back 1.00. The terms are a
     * whole number of days, blank putting back 14.
     */
    public record SettingsForm(@Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String clerkLimit,
            @Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String floor,
            @Pattern(regexp = "(\\d{1,5})?") String terms) {
    }

    private final AccessService service;

    public AccessController(AccessService service) {
        this.service = service;
    }

    @GetMapping("/access")
    public AccessView get(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        return new AccessView(service.roles(sheet).stream().map(RoleView::of).toList(),
                SettingsView.of(service.clerkLimit(sheet), service.floor(sheet), service.terms(sheet)));
    }

    @PutMapping("/roles")
    public RoleView setRole(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody RoleForm form) {
        Role role = Role.of(form.role()).orElseThrow();
        return RoleView.of(service.setRole(sheetId.trim(), user.trim(), form.user().trim(), role));
    }

    @PutMapping("/settings")
    public SettingsView setSettings(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody SettingsForm form) {
        String sheet = sheetId.trim();
        service.setSettings(sheet, user.trim(), form.clerkLimit(), form.floor(), form.terms());
        return SettingsView.of(service.clerkLimit(sheet), service.floor(sheet), service.terms(sheet));
    }

    /** Another user set a role for the same name at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void roleTaken() {
    }
}
