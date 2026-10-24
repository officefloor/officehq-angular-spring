package net.officefloor.hq.app.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.SheetAsOf;
import net.officefloor.hq.app.history.SheetHistory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's settings: its roles, its clerk limit, its floor, its cap, its terms, its late percent (each dated: saved with a from-date, the sheet's today when blank), its policy date and its period numbering from. The signed-in user's name travels in the
 * {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class SettingsController {

    /** A user name and the role it has on the sheet (clerk or steward). */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole role) {
            return new RoleView(role.getUser(), role.getRole().name().toLowerCase());
        }
    }

    /** The sheet's settings; the floor is sent as text with two decimals; the cap is sent as text with two decimals, null when there is none; the clerk limit is sent as text with two decimals, null when there is none; the terms in days; the grades' own terms in days, by grade (a grade not in it uses the sheet's terms); the late percent as text with two decimals; the policy date as set, null when it is the sheet's today; the period numbering from, null when it is off. The clerk limit, the floor, the cap, the terms, the grades' own terms and the late percent are those in force on the sheet's today; every floor, late percent and cap recorded, each from its date, oldest first, follow (a claim's floor and late percent are those in force on its date; a cap recorded as none is null); the proof threshold in force on the sheet's today, as text with two decimals, null when there is none, follows; then the permission matrix as set, the roles that may take each action by action (open, settle, void, reassign, close, reopen), blank for its standing rule; then the grace days, null when they are off; then the note threshold in force on the sheet's today, as text with two decimals, null when there is none; then the entry date, null when it is off; then the grades' grace days in force on the sheet's today, in days, by grade (a grade not in it has none); then every grace recorded for each grade, each from its date, oldest first, by grade (a grace recorded as none is null). */
    public record SettingsView(String clerkLimit, String floor, String cap, int terms, Map<String, Integer> gradeTerms, String latePct, LocalDate policyDate,
            List<RoleView> roles, LocalDate periodNumberingFrom, List<DatedView> floors, List<DatedView> latePcts, List<DatedView> caps,
            String proofThreshold, Map<String, String> permissions, Integer graceDays, String noteThreshold, LocalDate entryDate,
            Map<String, Integer> gradeGraces, Map<String, List<DatedView>> graces) {
    }

    /** The roles that may take an action: a comma separated list of clerk and steward; blank or null for
     * the action's standing rule. */
    public record PermissionRequest(@Size(max = 100) @Pattern(regexp = "\\s*((clerk|steward)\\s*(,\\s*(clerk|steward)\\s*)*)?") String value) {
        String rolesValue() {
            return value == null ? "" : value.replaceAll("\\s", "");
        }
    }

    /** A value of a dated setting, as text, and the date it is recorded from. */
    public record DatedView(LocalDate from, String value) {
        static DatedView of(SheetSetting setting) {
            return new DatedView(setting.getFrom(), setting.getValue());
        }
    }

    /** A role: clerk, steward or viewer. */
    public record RoleRequest(@NotNull @Pattern(regexp = "\\s*(clerk|steward|viewer)\\s*") String role) {
        Role roleValue() {
            return Role.valueOf(role.trim().toUpperCase());
        }
    }

    /** A clerk limit: 0.00 or more, with up to two decimals; blank or null for no limit. */
    public record ClerkLimitRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) {
        BigDecimal limitValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }

        LocalDate fromValue() {
            return fromOf(from);
        }
    }

    /** A floor: 0.00 or more, with up to two decimals. */
    public record FloorRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) {
        BigDecimal floorValue() {
            return new BigDecimal(value.trim()).setScale(2);
        }

        LocalDate fromValue() {
            return fromOf(from);
        }
    }

    /** A cap: 0.00 or more, with up to two decimals; blank or null for no cap. */
    public record CapRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) {
        BigDecimal capValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }

        LocalDate fromValue() {
            return fromOf(from);
        }
    }

    /** A proof or note threshold: 0.00 or more, with up to two decimals; blank or null for none. */
    public record ProofThresholdRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) {
        BigDecimal thresholdValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }

        LocalDate fromValue() {
            return fromOf(from);
        }
    }

    /** Terms: a whole number of business days, 0 or more. */
    public record TermsRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,4}\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) {
        int termsValue() {
            return Integer.parseInt(value.trim());
        }

        LocalDate fromValue() {
            return fromOf(from);
        }
    }

    /** A grade's own terms: a whole number of business days, 0 or more; blank or null to use the sheet's terms. */
    public record GradeTermsRequest(@Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) {
        Integer termsValue() {
            return value == null || value.isBlank() ? null : Integer.parseInt(value.trim());
        }

        LocalDate fromValue() {
            return fromOf(from);
        }
    }

    /** A grade's grace days: a whole number of days, 0 or more; blank or null for none. */
    public record GradeGraceRequest(@Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) {
        Integer daysValue() {
            return value == null || value.isBlank() ? null : Integer.parseInt(value.trim());
        }

        LocalDate fromValue() {
            return fromOf(from);
        }
    }

    /** A late percent: 0.00 or more, with up to two decimals. */
    public record LatePctRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) {
        BigDecimal latePctValue() {
            return new BigDecimal(value.trim()).setScale(2);
        }

        LocalDate fromValue() {
            return fromOf(from);
        }
    }

    /** A from-date (YYYY-MM-DD); null when blank or null, for the sheet's today. */
    private static LocalDate fromOf(String from) {
        return from == null || from.isBlank() ? null : LocalDate.parse(from.trim());
    }

    /** A policy date (YYYY-MM-DD); blank or null for the sheet's today. */
    public record PolicyDateRequest(@Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String value) {
        LocalDate dateValue() {
            return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
        }
    }

    /** A period numbering from date (YYYY-MM-DD); blank or null turns it off. */
    public record PeriodNumberingFromRequest(@Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String value) {
        LocalDate dateValue() {
            return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
        }
    }

    /** An entry date (YYYY-MM-DD); blank or null turns it off. */
    public record EntryDateRequest(@Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String value) {
        LocalDate dateValue() {
            return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
        }
    }

    /** Grace days, a whole number of days; blank or null turns them off. */
    public record GraceDaysRequest(@Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String value) {
        Integer daysValue() {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        }
    }

    private final SettingsService service;

    private final SheetHistory history;

    public SettingsController(SettingsService service, SheetHistory history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping("/settings")
    public SettingsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        String sheet = sheetId.trim();
        if (asOf != null) return viewOf(history.read(sheet, asOf));
        return new SettingsView(text(service.clerkLimit(sheet)), text(service.floor(sheet)),
                text(service.cap(sheet)), service.terms(sheet),
                service.gradeTerms(sheet),
                text(service.latePct(sheet)), service.policyDate(sheet), service.roles(sheet).stream().map(RoleView::of).toList(),
                service.periodNumberingFrom(sheet),
                service.history(sheet, DatedSettings.FLOOR).stream().map(DatedView::of).toList(),
                service.history(sheet, DatedSettings.LATE_PCT).stream().map(DatedView::of).toList(),
                service.history(sheet, DatedSettings.CAP).stream().map(DatedView::of).toList(),
                text(service.proofThreshold(sheet)), permissionsOf(service.permissions(sheet)), service.graceDays(sheet),
                text(service.noteThreshold(sheet)), service.entryDate(sheet), service.gradeGraces(sheet),
                gracesOf(grade -> service.history(sheet, DatedSettings.gradeGraceKey(grade))));
    }

    /** Every grace recorded for each grade, oldest first, by grade. */
    private static Map<String, List<DatedView>> gracesOf(Function<String, List<SheetSetting>> history) {
        Map<String, List<DatedView>> graces = new LinkedHashMap<>();
        for (String grade : List.of("A", "B", "C")) {
            graces.put(grade, history.apply(grade).stream().map(DatedView::of).toList());
        }
        return graces;
    }

    /** The settings as they stood on the sheet as of a date, that date standing in for its today. */
    private static SettingsView viewOf(SheetAsOf then) {
        LocalDate today = then.today();
        Map<String, Integer> gradeTerms = new LinkedHashMap<>();
        for (String grade : List.of("A", "B", "C")) {
            then.valueOn(DatedSettings.gradeTermsKey(grade), today).map(Integer::valueOf)
                    .ifPresent(own -> gradeTerms.put(grade, own));
        }
        Map<String, Integer> gradeGraces = new LinkedHashMap<>();
        for (String grade : List.of("A", "B", "C")) {
            Integer grace = then.graceOn(grade, today);
            if (grace != null) gradeGraces.put(grade, grace);
        }
        Map<Action, String> matrix = new EnumMap<>(Action.class);
        for (Action action : Action.values()) matrix.put(action, "");
        for (SheetPermission set : then.permissions()) matrix.put(set.getAction(), set.getRoles());
        return new SettingsView(
                text(then.valueOn(DatedSettings.CLERK_LIMIT, today).map(BigDecimal::new).orElse(null)),
                text(then.floorOn(today)), text(then.cap()),
                then.valueOn(DatedSettings.TERMS, today).map(Integer::valueOf).orElse(DatedSettings.TERMS_START),
                gradeTerms, text(then.latePctOn(today)), then.policyDate(),
                then.roles().stream().map(RoleView::of).toList(), then.periodNumberingFrom(),
                then.history(DatedSettings.FLOOR).stream().map(DatedView::of).toList(),
                then.history(DatedSettings.LATE_PCT).stream().map(DatedView::of).toList(),
                then.history(DatedSettings.CAP).stream().map(DatedView::of).toList(),
                text(then.valueOn(DatedSettings.PROOF_THRESHOLD, today).map(BigDecimal::new).orElse(null)),
                permissionsOf(matrix), then.graceDays(),
                text(then.valueOn(DatedSettings.NOTE_THRESHOLD, today).map(BigDecimal::new).orElse(null)),
                then.entryDate(), gradeGraces,
                gracesOf(grade -> then.history(DatedSettings.gradeGraceKey(grade))));
    }

    private static Map<String, String> permissionsOf(Map<Action, String> matrix) {
        Map<String, String> byKey = new LinkedHashMap<>();
        matrix.forEach((action, roles) -> byKey.put(action.key(), roles));
        return byKey;
    }

    @PutMapping("/settings/perm/{action}")
    public SettingsView setPermission(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "open|settle|void|reassign|close|reopen") String action,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PermissionRequest request) {
        String sheet = sheetId.trim();
        service.setPermission(sheet, user.trim(), Action.valueOf(action.toUpperCase(Locale.ROOT)), request.rolesValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClerkLimitRequest request) {
        String sheet = sheetId.trim();
        service.setClerkLimit(sheet, user.trim(), request.limitValue(), request.fromValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/cap")
    public SettingsView setCap(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CapRequest request) {
        String sheet = sheetId.trim();
        service.setCap(sheet, user.trim(), request.capValue(), request.fromValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/proof-threshold")
    public SettingsView setProofThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ProofThresholdRequest request) {
        String sheet = sheetId.trim();
        service.setProofThreshold(sheet, user.trim(), request.thresholdValue(), request.fromValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/note-threshold")
    public SettingsView setNoteThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ProofThresholdRequest request) {
        String sheet = sheetId.trim();
        service.setNoteThreshold(sheet, user.trim(), request.thresholdValue(), request.fromValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FloorRequest request) {
        String sheet = sheetId.trim();
        service.setFloor(sheet, user.trim(), request.floorValue(), request.fromValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/terms")
    public SettingsView setTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TermsRequest request) {
        String sheet = sheetId.trim();
        service.setTerms(sheet, user.trim(), request.termsValue(), request.fromValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/terms/{grade}")
    public SettingsView setGradeTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GradeTermsRequest request) {
        String sheet = sheetId.trim();
        service.setGradeTerms(sheet, user.trim(), grade, request.termsValue(), request.fromValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/grace/{grade}")
    public SettingsView setGradeGrace(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GradeGraceRequest request) {
        String sheet = sheetId.trim();
        service.setGradeGrace(sheet, user.trim(), grade, request.daysValue(), request.fromValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/late-pct")
    public SettingsView setLatePct(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid LatePctRequest request) {
        String sheet = sheetId.trim();
        service.setLatePct(sheet, user.trim(), request.latePctValue(), request.fromValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/policy-date")
    public SettingsView setPolicyDate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PolicyDateRequest request) {
        String sheet = sheetId.trim();
        service.setPolicyDate(sheet, user.trim(), request.dateValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/period-numbering-from")
    public SettingsView setPeriodNumberingFrom(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PeriodNumberingFromRequest request) {
        String sheet = sheetId.trim();
        service.setPeriodNumberingFrom(sheet, user.trim(), request.dateValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/grace-days")
    public SettingsView setGraceDays(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GraceDaysRequest request) {
        String sheet = sheetId.trim();
        service.setGraceDays(sheet, user.trim(), request.daysValue());
        return get(sheet, user, null);
    }

    @PutMapping("/settings/entry-date")
    public SettingsView setEntryDate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid EntryDateRequest request) {
        String sheet = sheetId.trim();
        service.setEntryDate(sheet, user.trim(), request.dateValue());
        return get(sheet, user, null);
    }

    @PutMapping("/roles/{name}")
    public RoleView setRole(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RoleRequest request) {
        return RoleView.of(service.setRole(sheetId.trim(), user.trim(), name.trim(), request.roleValue()));
    }

    private static String text(BigDecimal figure) {
        return figure == null ? null : figure.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
