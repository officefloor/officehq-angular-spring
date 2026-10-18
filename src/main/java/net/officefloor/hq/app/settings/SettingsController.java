package net.officefloor.hq.app.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
import net.officefloor.hq.app.sheet.Sheet;
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
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class SettingsController {

    /**
     * The sheet's settings, as text with two decimals: the clerk limit (null when there is none) and
     * the floor; the terms in days; the late percent, with two decimals; and the policy date
     * (yyyy-mm-dd, null when there is none: the sheet's today is used).
     */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, String policyDate) {
        static SettingsView of(Sheet sheet) {
            BigDecimal limit = sheet.getClerkLimit();
            return new SettingsView(limit == null ? null : limit.setScale(2).toPlainString(),
                    sheet.getFloor().setScale(2).toPlainString(), sheet.getTerms(),
                    sheet.getLatePct().setScale(2).toPlainString(),
                    sheet.getPolicyDate() == null ? null : sheet.getPolicyDate().toString());
        }
    }

    /**
     * The settings to save; a setting left out (null) is not changed. The clerk limit is 0.00 or more
     * with up to two decimals, blank for no limit; the floor is 0.00 or more with up to two decimals; the terms are a whole number of days, 0 or more; the late percent is 0.00 or more with up to two decimals; the policy date is yyyy-mm-dd, blank for none.
     */
    public record SettingsForm(
            @Pattern(regexp = "\\s*|\\d{1,13}(\\.\\d{1,2})?") String clerkLimit,
            @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?") String floor,
            @Pattern(regexp = "\\d{1,4}") String terms,
            @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String latePct,
            @Pattern(regexp = "\\s*|\\d{4}-\\d{2}-\\d{2}") String policyDate) {

        BigDecimal clerkLimitValue() {
            return clerkLimit == null || clerkLimit.isBlank() ? null : new BigDecimal(clerkLimit).setScale(2);
        }

        BigDecimal floorValue() {
            return floor == null ? null : new BigDecimal(floor).setScale(2);
        }

        Integer termsValue() {
            return terms == null ? null : Integer.valueOf(terms);
        }

        BigDecimal latePctValue() {
            return latePct == null ? null : new BigDecimal(latePct).setScale(2);
        }

        LocalDate policyDateValue() {
            if (policyDate == null || policyDate.isBlank()) return null;
            try {
                return LocalDate.parse(policyDate);
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + policyDate);
            }
        }
    }

    /** A user name's role, in lower case (clerk or steward). */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole role) {
            return new RoleView(role.getUserName(), role.getRole().name().toLowerCase());
        }
    }

    public record RoleForm(
            @NotBlank @Size(max = 100) String user,
            @NotNull @Pattern(regexp = "clerk|steward") String role) {

        Role roleValue() {
            return Role.valueOf(role.toUpperCase());
        }
    }

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public SettingsView settings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return SettingsView.of(service.settings(sheetId, user));
    }

    /** Save the sheet's settings given; one left out keeps its value. */
    @PutMapping("/settings")
    public SettingsView save(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody SettingsForm form) {
        return SettingsView.of(service.save(sheetId, user, form.clerkLimit() != null, form.clerkLimitValue(),
                form.floorValue(), form.termsValue(), form.latePctValue(), form.policyDate() != null,
                form.policyDateValue()));
    }

    /** The roles the sheet gives user names, by name. */
    @GetMapping("/roles")
    public List<RoleView> roles(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return service.roles(sheetId).stream().map(RoleView::of).toList();
    }

    /** Give a user name a role (clerk or steward); any other role is refused (400). */
    @PutMapping("/roles")
    public RoleView setRole(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody RoleForm form) {
        return RoleView.of(service.setRole(sheetId, user, form.user().trim(), form.roleValue()));
    }
}
