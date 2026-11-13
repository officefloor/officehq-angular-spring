package net.officefloor.hq.app.setting;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.rollup.RollupService;
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

    /** The sheet's settings: the roles assigned, the clerk limit (two decimals; null for none) and
     *  the floor (two decimals), the terms (whole days), the late percent (two decimals) and the policy
     *  date (blank for the sheet's today) and the period numbering from (blank for off). */
    public record SettingsView(List<RoleView> roles, String clerkLimit, String floor, int terms, String latePct,
            String policyDate, String periodNumberingFrom) {
    }

    /** A role for a user name: clerk or steward. */
    public record RoleRequest(@NotBlank @Size(max = 200) String user, @NotBlank String role) {
    }

    /** A clerk limit of 0.00 or more with up to two decimals; null removes the limit. */
    public record ClerkLimitRequest(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value) {
    }

    /** A floor of 0.00 or more with up to two decimals. */
    public record FloorRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value) {
    }

    /** Terms of 0 or more whole days. */
    public record TermsRequest(@NotNull @Min(0) @Max(36500) Integer value) {
    }

    /** A late percent of 0.00 or more with up to two decimals. */
    public record LatePctRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value) {
    }

    /** A policy date; null leaves it blank (the sheet's today). */
    public record PolicyDateRequest(LocalDate value) {
    }

    /** A period numbering from date; null leaves it blank (off). */
    public record PeriodNumberingFromRequest(LocalDate value) {
    }

    private final SettingService service;
    private final RollupService rollups;

    public SettingController(SettingService service, RollupService rollups) {
        this.service = service;
        this.rollups = rollups;
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

    /** Change the sheet's floor; the figures it moves are followed in the audit. */
    @PutMapping("/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated FloorRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        rollups.track(sheet, by, () -> service.setFloor(sheet, by, request.value()));
        return view(sheet);
    }

    /** Change the sheet's terms; the late charges it moves are followed in the audit. */
    @PutMapping("/terms")
    public SettingsView setTerms(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated TermsRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        rollups.track(sheet, by, () -> service.setTerms(sheet, by, request.value()));
        return view(sheet);
    }

    /** Change the sheet's late percent; the figures it moves are followed in the audit. */
    @PutMapping("/late-pct")
    public SettingsView setLatePct(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated LatePctRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        rollups.track(sheet, by, () -> service.setLatePct(sheet, by, request.value()));
        return view(sheet);
    }

    /** Change the sheet's policy date; the figures it moves are followed in the audit. */
    @PutMapping("/policy-date")
    public SettingsView setPolicyDate(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated PolicyDateRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        rollups.track(sheet, by, () -> service.setPolicyDate(sheet, by, request.value()));
        return view(sheet);
    }

    /** Change the sheet's period numbering from; it numbers only claims opened from then on. */
    @PutMapping("/period-numbering-from")
    public SettingsView setPeriodNumberingFrom(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated PeriodNumberingFromRequest request) {
        String sheet = sheetId.trim();
        service.setPeriodNumberingFrom(sheet, SignedInUser.decode(user), request.value());
        return view(sheet);
    }

    private SettingsView view(String sheet) {
        BigDecimal limit = service.clerkLimit(sheet);
        LocalDate policyDate = service.policyDateSet(sheet);
        LocalDate numberingFrom = service.periodNumberingFrom(sheet);
        return new SettingsView(service.roles(sheet).stream().map(RoleView::of).toList(),
                limit != null ? limit.toPlainString() : null, service.floor(sheet).toPlainString(), service.terms(sheet),
                service.latePct(sheet).toPlainString(), policyDate != null ? policyDate.toString() : "",
                numberingFrom != null ? numberingFrom.toString() : "");
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
