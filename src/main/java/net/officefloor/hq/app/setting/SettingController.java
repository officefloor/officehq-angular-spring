package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    /**
     * The sheet's settings as the screen shows them; the clerk limit has two decimals, null when none,
     * and so has the floor; the terms are in days; the late percent has two decimals; the policy date
     * is null when blank (the sheet's today).
     */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            List<RoleView> roles) {}

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

    /** A floor with at most two decimals. */
    public record FloorRequest(@NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String value) {

        BigDecimal floorValue() {
            return new BigDecimal(value).setScale(2);
        }
    }

    /** A late percent with at most two decimals. */
    public record LatePctRequest(@NotNull @Pattern(regexp = "\\d{1,5}(\\.\\d{1,2})?") String value) {

        BigDecimal latePctValue() {
            return new BigDecimal(value).setScale(2);
        }
    }

    /** A policy date (yyyy-mm-dd); blank or null: the sheet's today. */
    public record PolicyDateRequest(@Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String value) {

        LocalDate policyDateValue() {
            return value == null || value.isEmpty() ? null : LocalDate.parse(value);
        }
    }

    /** Terms: a whole number of days. */
    public record TermsRequest(@NotNull @Pattern(regexp = "\\d{1,4}") String value) {

        int termsValue() {
            return Integer.parseInt(value);
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
        return new SettingsView(text(service.clerkLimit(sheetId)), text(service.floor(sheetId)),
                service.terms(sheetId), text(service.latePct(sheetId)), service.policyDate(sheetId),
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

    @PutMapping("/settings/floor")
    public SettingsView setFloor(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FloorRequest request) {
        service.setFloor(sheetId, user, request.floorValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/terms")
    public SettingsView setTerms(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TermsRequest request) {
        service.setTerms(sheetId, user, request.termsValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/late-pct")
    public SettingsView setLatePct(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid LatePctRequest request) {
        service.setLatePct(sheetId, user, request.latePctValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/policy-date")
    public SettingsView setPolicyDate(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PolicyDateRequest request) {
        service.setPolicyDate(sheetId, user, request.policyDateValue());
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
