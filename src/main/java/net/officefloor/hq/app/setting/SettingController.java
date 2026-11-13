package net.officefloor.hq.app.setting;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/settings")
public class SettingController {

    /** A user name's role as the screen shows it: clerk or steward. */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole role) {
            return new RoleView(role.getUser(), role.getRole().label());
        }
    }

    /** The sheet's settings: the roles assigned, and the clerk limit (two decimals; null for none). */
    public record SettingsView(List<RoleView> roles, String clerkLimit) {
    }

    /** A role for a user name: clerk or steward. */
    public record RoleRequest(@NotBlank @Size(max = 200) String user, @NotBlank String role) {
    }

    /** A clerk limit of 0.00 or more with up to two decimals; null removes the limit. */
    public record ClerkLimitRequest(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value) {
    }

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    /** The sheet's settings. */
    @GetMapping
    public SettingsView get(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        String sheet = sheetId.trim();
        return view(sheet);
    }

    /** Assign a user name a role; a role other than clerk or steward is refused. */
    @PutMapping("/roles")
    public RoleView setRole(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated RoleRequest request) {
        Role role = Role.of(request.role().trim())
                .orElseThrow(() -> new IllegalArgumentException("No role " + request.role()));
        return RoleView.of(service.setRole(sheetId.trim(), SignedInUser.decode(user), request.user().trim(), role));
    }

    /** Change the sheet's clerk limit. */
    @PutMapping("/clerk-limit")
    public SettingsView setClerkLimit(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated ClerkLimitRequest request) {
        String sheet = sheetId.trim();
        service.setClerkLimit(sheet, SignedInUser.decode(user), request.value());
        return view(sheet);
    }

    private SettingsView view(String sheet) {
        BigDecimal limit = service.clerkLimit(sheet);
        return new SettingsView(service.roles(sheet).stream().map(RoleView::of).toList(),
                limit != null ? limit.toPlainString() : null);
    }

    /** A role that is not clerk or steward, or a limit that is not a number, is refused. */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }

    /** The user name's role was first set concurrently; caught by the unique index. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void conflict() {
    }
}
