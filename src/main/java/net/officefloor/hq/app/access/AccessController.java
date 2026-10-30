package net.officefloor.hq.app.access;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.sheet.DatedSettings;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
     * The sheet's settings as shown, each dated one the value in force on the sheet's today; a limit is a
     * figure with two decimals, null for none; so is the floor;
     * the terms are a number of days, and so are the terms of grades A, B and C, null for a grade without
     * its own (it uses the sheet's); the late percent is a figure with two decimals; the policy date and
     * the period numbering from are dates (yyyy-mm-dd), null when blank; the grace days are a number of days, null
     * when blank (off); the cap is a figure with two
     * decimals, null for none; so is the proof threshold. Each entry of the permission matrix (open, settle,
     * void, reassign, close, reopen) is the roles that may take the action as written, comma separated; null
     * when blank (the standing rule).
     */
    public record SettingsView(String clerkLimit, String floor, String terms, String termsA, String termsB,
            String termsC, String latePct, String policyDate, String periodNumberingFrom, String graceDays,
            String cap, String proofThreshold, String permOpen, String permSettle, String permVoid, String permReassign,
            String permClose, String permReopen) {

        static SettingsView of(DatedSettings dated, LocalDate today, LocalDate policyDate, LocalDate periodNumberingFrom,
                Integer graceDays) {
            return new SettingsView(text(dated.clerkLimit(today)), dated.floor(today).toPlainString(),
                    Integer.toString(dated.terms(today)), text(dated.gradeTerms("A", today)),
                    text(dated.gradeTerms("B", today)), text(dated.gradeTerms("C", today)),
                    dated.latePct(today).toPlainString(),
                    policyDate == null ? null : policyDate.toString(),
                    periodNumberingFrom == null ? null : periodNumberingFrom.toString(), text(graceDays),
                    text(dated.cap(today)), text(dated.proofThreshold(today)),
                    perm(dated, Permission.OPEN, today), perm(dated, Permission.SETTLE, today),
                    perm(dated, Permission.VOID, today), perm(dated, Permission.REASSIGN, today),
                    perm(dated, Permission.CLOSE, today), perm(dated, Permission.REOPEN, today));
        }

        private static String perm(DatedSettings dated, Permission permission, LocalDate today) {
            return dated.on(permission.key(), today).orElse(null);
        }

        private static String text(Object value) {
            return value == null ? null : value instanceof BigDecimal d ? d.toPlainString() : value.toString();
        }
    }

    /** A delegation of the steward role: the user, the steward who delegated, and its dates (yyyy-mm-dd), inclusive. */
    public record DelegationView(long id, String user, String by, String from, String to) {

        static DelegationView of(Delegation d) {
            return new DelegationView(d.getId(), d.getUserName(), d.getDelegatedBy(), d.getFromDate().toString(),
                    d.getToDate().toString());
        }
    }

    public record AccessView(List<RoleView> roles, SettingsView settings, List<DelegationView> delegations) {
    }

    /** A user name and the dates (yyyy-mm-dd) the steward role is delegated to it for; a blank from-date is the sheet's today. */
    public record DelegateForm(@NotBlank @Size(max = 200) String user, @Pattern(regexp = DATE) String from,
            @NotBlank @Pattern(regexp = DATE) String to) {
    }

    /** A user name and a role: clerk, steward or viewer. */
    public record RoleForm(@NotBlank @Size(max = 200) String user, @NotNull @Pattern(regexp = "clerk|steward|viewer") String role) {
    }

    /**
     * The settings to save; one left out (null) is kept as it is. A clerk limit is 0.00 or more with up
     * to two decimals, blank meaning no limit; so is a floor, blank putting back 1.00. The terms are a
     * whole number of days, blank putting back 14; so are a grade's own terms, blank removing them (the
     * grade then uses the sheet's). The late percent is 0.00 or more with up to two
     * decimals, blank putting back 0.00. The policy date is a date (yyyy-mm-dd), blank meaning the
     * sheet's today. The period numbering from is a date (yyyy-mm-dd), blank meaning off. The grace days are a whole number of
     * days, blank meaning off; not dated. The cap is 0.00
     * or more with up to two decimals, blank meaning no cap; so is the proof threshold, blank meaning none. Each setting that carries a figure takes a
     * from-date (yyyy-mm-dd) its value is in force from, blank or left out meaning the sheet's today. Each
     * entry of the permission matrix is a comma separated list of roles (clerk, steward), blank putting back
     * the standing rule; it is not dated.
     */
    public record SettingsForm(@Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String clerkLimit,
            @Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String floor,
            @Pattern(regexp = "(\\d{1,5})?") String terms,
            @Pattern(regexp = "(\\d{1,5})?") String termsA,
            @Pattern(regexp = "(\\d{1,5})?") String termsB,
            @Pattern(regexp = "(\\d{1,5})?") String termsC,
            @Pattern(regexp = "(\\d{1,10}(\\.\\d{1,2})?)?") String latePct,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String policyDate,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String periodNumberingFrom,
            @Pattern(regexp = "(\\d{1,5})?") String graceDays,
            @Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String cap,
            @Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String proofThreshold,
            @Pattern(regexp = DATE) String clerkLimitFrom,
            @Pattern(regexp = DATE) String floorFrom,
            @Pattern(regexp = DATE) String termsFrom,
            @Pattern(regexp = DATE) String termsAFrom,
            @Pattern(regexp = DATE) String termsBFrom,
            @Pattern(regexp = DATE) String termsCFrom,
            @Pattern(regexp = DATE) String latePctFrom,
            @Pattern(regexp = DATE) String capFrom,
            @Pattern(regexp = DATE) String proofThresholdFrom,
            @Pattern(regexp = Permission.ROLES) String permOpen,
            @Pattern(regexp = Permission.ROLES) String permSettle,
            @Pattern(regexp = Permission.ROLES) String permVoid,
            @Pattern(regexp = Permission.ROLES) String permReassign,
            @Pattern(regexp = Permission.ROLES) String permClose,
            @Pattern(regexp = Permission.ROLES) String permReopen) {
    }

    /** A from-date: a date (yyyy-mm-dd), or blank for the sheet's today. */
    private static final String DATE = "(\\d{4}-\\d{2}-\\d{2})?";

    private final AccessService service;

    public AccessController(AccessService service) {
        this.service = service;
    }

    @GetMapping("/access")
    public AccessView get(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        return new AccessView(service.roles(sheet).stream().map(RoleView::of).toList(),
                settings(sheet), delegations(sheet));
    }

    @PostMapping("/delegations")
    public List<DelegationView> delegate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody DelegateForm form) {
        String sheet = sheetId.trim();
        LocalDate from = form.from() == null || form.from().isEmpty() ? service.today(sheet) : LocalDate.parse(form.from());
        service.delegate(sheet, user.trim(), form.user().trim(), from, LocalDate.parse(form.to()));
        return delegations(sheet);
    }

    private List<DelegationView> delegations(String sheet) {
        return service.delegations(sheet).stream().map(DelegationView::of).toList();
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
        Map<String, AccessService.Dated> gradeTerms = new LinkedHashMap<>();
        gradeTerms.put("A", dated(form.termsA(), form.termsAFrom()));
        gradeTerms.put("B", dated(form.termsB(), form.termsBFrom()));
        gradeTerms.put("C", dated(form.termsC(), form.termsCFrom()));
        AccessService.Dated clerkLimit = dated(form.clerkLimit(), form.clerkLimitFrom());
        AccessService.Dated floor = dated(form.floor(), form.floorFrom());
        AccessService.Dated terms = dated(form.terms(), form.termsFrom());
        AccessService.Dated latePct = dated(form.latePct(), form.latePctFrom());
        AccessService.Dated cap = dated(form.cap(), form.capFrom());
        Map<Permission, String> permissions = new EnumMap<>(Permission.class);
        permissions.put(Permission.OPEN, form.permOpen());
        permissions.put(Permission.SETTLE, form.permSettle());
        permissions.put(Permission.VOID, form.permVoid());
        permissions.put(Permission.REASSIGN, form.permReassign());
        permissions.put(Permission.CLOSE, form.permClose());
        permissions.put(Permission.REOPEN, form.permReopen());
        service.setSettings(sheet, user.trim(), clerkLimit, floor, terms, gradeTerms, latePct, form.policyDate(),
                form.periodNumberingFrom(), form.graceDays(), cap, dated(form.proofThreshold(), form.proofThresholdFrom()), permissions);
        return settings(sheet);
    }

    private SettingsView settings(String sheet) {
        return SettingsView.of(service.settings(sheet), service.today(sheet), service.policyDate(sheet),
                service.periodNumberingFrom(sheet), service.graceDays(sheet));
    }

    /** A dated setting's value with its from-date (blank or left out: the sheet's today); null when the value is left out. */
    private static AccessService.Dated dated(String value, String from) {
        return value == null ? null
                : new AccessService.Dated(value, from == null || from.isEmpty() ? null : LocalDate.parse(from));
    }

    /** A policy date, period numbering from or from-date that is no date on the calendar: the save is refused and changes nothing. */
    @ExceptionHandler(DateTimeParseException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void noSuchDate() {
    }

    /** A delegation by anyone but a steward, or with its to-date before its from-date: refused, changing nothing. */
    @ExceptionHandler(DelegationRefused.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void delegationRefused() {
    }

    /** Another user set a role for the same name at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void roleTaken() {
    }
}
