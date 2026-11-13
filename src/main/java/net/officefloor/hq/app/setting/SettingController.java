package net.officefloor.hq.app.setting;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
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

    /** The sheet's settings: the roles assigned, and as in force on the sheet's today the clerk limit (two decimals; null for none) and
     *  the floor (two decimals), the cap (two decimals; null for none), the terms (whole days), the late percent (two decimals) and the policy
     *  date (blank for the sheet's today) and the period numbering from (blank for off), and the grades'
     *  own terms by grade (a grade not listed takes the sheet's terms); and every value recorded for the
     *  dated settings, by key (floor, late-pct, terms, terms-<grade>, clerk-limit, cap, proof-threshold); and
     *  the permission matrix, the roles each action allows by action (blank for the standing rule). */
    public record SettingsView(List<RoleView> roles, String clerkLimit, String floor, String cap, int terms, String latePct,
            String policyDate, String periodNumberingFrom, Map<String, Integer> gradeTerms,
            Map<String, List<DatedView>> dated, Map<String, String> permissions) {
    }

    /** A value recorded for a dated setting, in force from its from-date: two decimals, whole days for
     *  terms, null for no clerk limit, cap or proof threshold. */
    public record DatedView(String from, String value) {
        static DatedView of(SettingValue v) {
            String value = v.getValue() == null ? null
                    : v.getKey().startsWith("terms") ? String.valueOf(v.getValue().intValue())
                    : v.getValue().toPlainString();
            return new DatedView(v.getFrom().toString(), value);
        }
    }

    /** A role for a user name: clerk or steward. */
    public record RoleRequest(@NotBlank @Size(max = 200) String user, @NotBlank String role) {
    }

    /** A clerk limit of 0.00 or more with up to two decimals; null removes the limit. */
    public record ClerkLimitRequest(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value, LocalDate from) {
    }

    /** A floor of 0.00 or more with up to two decimals. */
    public record FloorRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value, LocalDate from) {
    }

    /** A cap of 0.00 or more with up to two decimals; null removes the cap. */
    public record CapRequest(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value, LocalDate from) {
    }

    /** A proof threshold of 0.00 or more with up to two decimals; null removes the threshold. */
    public record ProofThresholdRequest(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value, LocalDate from) {
    }

    /** Terms of 0 or more whole days. Each figure setting is recorded from its from-date; null for the
     *  sheet's today. */
    public record TermsRequest(@NotNull @Min(0) @Max(36500) Integer value, LocalDate from) {
    }

    /** A late percent of 0.00 or more with up to two decimals. */
    public record LatePctRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value, LocalDate from) {
    }

    /** The roles that may take an action, comma separated (clerk, steward); blank keeps the standing rule. */
    public record PermissionRequest(@Size(max = 200) String value) {
    }

    /** A policy date; null leaves it blank (the sheet's today). */
    public record PolicyDateRequest(LocalDate value) {
    }

    /** A period numbering from date; null leaves it blank (off). */
    public record PeriodNumberingFromRequest(LocalDate value) {
    }

    private final SettingService service;
    private final RollupService rollups;
    private final SheetService sheets;

    public SettingController(SettingService service, RollupService rollups, SheetService sheets) {
        this.service = service;
        this.rollups = rollups;
        this.sheets = sheets;
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
        service.setClerkLimit(sheet, SignedInUser.decode(user), request.value(), request.from());
        return view(sheet);
    }

    /** Change the sheet's floor; the figures it moves are followed in the audit. */
    @PutMapping("/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated FloorRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        rollups.track(sheet, by, () -> service.setFloor(sheet, by, request.value(), request.from()));
        return view(sheet);
    }

    /** Change the sheet's cap; the figures it moves are followed in the audit. */
    @PutMapping("/cap")
    public SettingsView setCap(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated CapRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        rollups.track(sheet, by, () -> service.setCap(sheet, by, request.value(), request.from()));
        return view(sheet);
    }

    /** Change the sheet's proof threshold. */
    @PutMapping("/proof-threshold")
    public SettingsView setProofThreshold(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated ProofThresholdRequest request) {
        String sheet = sheetId.trim();
        service.setProofThreshold(sheet, SignedInUser.decode(user), request.value(), request.from());
        return view(sheet);
    }

    /** Change the sheet's terms; the late charges it moves are followed in the audit. */
    @PutMapping("/terms")
    public SettingsView setTerms(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated TermsRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        rollups.track(sheet, by, () -> service.setTerms(sheet, by, request.value(), request.from()));
        return view(sheet);
    }

    /** Set a grade's (A, B or C) own terms; they fix the due-by of the grade's claims dated from their from-date that are added from now on. */
    @PutMapping("/terms/{grade}")
    public SettingsView setGradeTerms(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated TermsRequest request) {
        String sheet = sheetId.trim();
        service.setGradeTerms(sheet, SignedInUser.decode(user), grade, request.value(), request.from());
        return view(sheet);
    }

    /** Change the sheet's late percent; the figures it moves are followed in the audit. */
    @PutMapping("/late-pct")
    public SettingsView setLatePct(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated LatePctRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        rollups.track(sheet, by, () -> service.setLatePct(sheet, by, request.value(), request.from()));
        return view(sheet);
    }

    /** Set the roles that may take an action (open, settle, void, reassign, close or reopen); a role
     *  other than clerk or steward is refused. */
    @PutMapping("/permissions/{action}")
    public SettingsView setPermission(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable String action, @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated PermissionRequest request) {
        String sheet = sheetId.trim();
        Permission permission = Permission.of(action)
                .orElseThrow(() -> new IllegalArgumentException("No action " + action));
        String value = request.value() == null ? "" : request.value().trim();
        List<Role> allowed = value.isEmpty() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim)
                        .map(label -> Role.of(label).orElseThrow(() -> new IllegalArgumentException("No role " + label)))
                        .distinct().toList();
        service.setPermission(sheet, SignedInUser.decode(user), permission, allowed);
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
        LocalDate today = sheets.today(sheet);
        BigDecimal limit = service.clerkLimit(sheet);
        BigDecimal cap = service.cap(sheet);
        LocalDate policyDate = service.policyDateSet(sheet);
        LocalDate numberingFrom = service.periodNumberingFrom(sheet);
        Map<String, List<DatedView>> dated = new LinkedHashMap<>();
        service.dated(sheet).forEach((key, recorded) -> dated.put(key, recorded.stream().map(DatedView::of).toList()));
        return new SettingsView(service.roles(sheet).stream().map(RoleView::of).toList(),
                limit != null ? limit.toPlainString() : null, service.floor(sheet, today).toPlainString(),
                cap != null ? cap.toPlainString() : null, service.terms(sheet, today),
                service.latePct(sheet, today).toPlainString(), policyDate != null ? policyDate.toString() : "",
                numberingFrom != null ? numberingFrom.toString() : "", service.gradeTerms(sheet, today), dated,
                service.permissions(sheet));
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
