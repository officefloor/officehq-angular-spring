package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.sheet.DatedSetting;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
     * The sheet's settings as the screen shows them, each dated one as in force on the sheet's today,
     * with every value recorded for those in dated; the clerk limit has two decimals, null when none,
     * and so has the floor; the terms are in days, and so are gradeTerms, by grade, for each grade with
     * its own; the late percent has two decimals; the policy date
     * is null when blank (the sheet's today), and so is the period numbering from (off); the cap has
     * two decimals, null when none; permissions has the roles set for each action of the permission
     * matrix, comma separated, for each action with any set (the rest keep the standing rule); the grace
     * days are a whole number of days, null when blank (off); the entry date is null when blank (off); the pot has two decimals, null when none;
     * the parent is the id of the sheet it names as its parent, null when none; the fiscal start month
     * is the month (1 to 12) a fiscal year starts in; the alert threshold has two decimals, null when off.
     */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            LocalDate periodNumberingFrom, List<RoleView> roles, Map<String, Integer> gradeTerms, String cap,
            Map<String, List<DatedView>> dated, Map<String, String> permissions, Integer graceDays,
            LocalDate entryDate, String pot, String parent, int fiscalStartMonth, String alertThreshold) {}

    /** A value recorded for a dated setting from a date on; null when none. */
    public record DatedView(LocalDate from, String value) {}

    /** The values recorded for each dated setting, by key, earliest from-date first. */
    public static Map<String, List<DatedView>> datedViews(List<DatedSetting> dated) {
        Map<String, List<DatedView>> byKey = new TreeMap<>();
        for (DatedSetting d : dated) {
            byKey.computeIfAbsent(d.getKey(), k -> new ArrayList<>()).add(new DatedView(d.getFrom(), d.getValue()));
        }
        return byKey;
    }

    /** A user name's role, as clerk or steward. */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole r) {
            return new RoleView(r.getUserName(), r.getRole().label());
        }
    }

    /** A clerk limit with at most two decimals; blank or null: no limit; recorded from the from-date (yyyy-mm-dd), blank or null: the sheet's today. */
    public record ClerkLimitRequest(@Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String from) {

        LocalDate fromValue() {
            return from == null || from.isEmpty() ? null : LocalDate.parse(from);
        }

        BigDecimal limitValue() {
            return value == null || value.isEmpty() ? null : new BigDecimal(value).setScale(2);
        }
    }

    /** A cap with at most two decimals; blank or null: none; recorded from the from-date (yyyy-mm-dd), blank or null: the sheet's today. */
    public record CapRequest(@Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String from) {

        LocalDate fromValue() {
            return from == null || from.isEmpty() ? null : LocalDate.parse(from);
        }

        BigDecimal capValue() {
            return value == null || value.isEmpty() ? null : new BigDecimal(value).setScale(2);
        }
    }

    /** A proof threshold with at most two decimals; blank or null: none; recorded from the from-date (yyyy-mm-dd), blank or null: the sheet's today. */
    public record ProofThresholdRequest(@Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String from) {

        LocalDate fromValue() {
            return from == null || from.isEmpty() ? null : LocalDate.parse(from);
        }

        BigDecimal thresholdValue() {
            return value == null || value.isEmpty() ? null : new BigDecimal(value).setScale(2);
        }
    }

    /** A note threshold with at most two decimals; blank or null: none; recorded from the from-date (yyyy-mm-dd), blank or null: the sheet's today. */
    public record NoteThresholdRequest(@Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String from) {

        LocalDate fromValue() {
            return from == null || from.isEmpty() ? null : LocalDate.parse(from);
        }

        BigDecimal thresholdValue() {
            return value == null || value.isEmpty() ? null : new BigDecimal(value).setScale(2);
        }
    }

    /** A review threshold with at most two decimals; blank or null: none; recorded from the from-date (yyyy-mm-dd), blank or null: the sheet's today. */
    public record ReviewThresholdRequest(@Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String from) {

        LocalDate fromValue() {
            return from == null || from.isEmpty() ? null : LocalDate.parse(from);
        }

        BigDecimal thresholdValue() {
            return value == null || value.isEmpty() ? null : new BigDecimal(value).setScale(2);
        }
    }

    /** A floor with at most two decimals; recorded from the from-date (yyyy-mm-dd), blank or null: the sheet's today. */
    public record FloorRequest(@NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String value,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String from) {

        LocalDate fromValue() {
            return from == null || from.isEmpty() ? null : LocalDate.parse(from);
        }

        BigDecimal floorValue() {
            return new BigDecimal(value).setScale(2);
        }
    }

    /** A rounding mode, half-up or half-even; blank or null: none; recorded from the from-date (yyyy-mm-dd), blank or null: the sheet's today. */
    public record RoundingRequest(@Pattern(regexp = "(half-up|half-even)?") String value,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String from) {

        LocalDate fromValue() {
            return from == null || from.isEmpty() ? null : LocalDate.parse(from);
        }

        String roundingValue() {
            return value == null || value.isEmpty() ? null : value;
        }
    }

    /** A late percent with at most two decimals; recorded from the from-date (yyyy-mm-dd), blank or null: the sheet's today. */
    public record LatePctRequest(@NotNull @Pattern(regexp = "\\d{1,5}(\\.\\d{1,2})?") String value,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String from) {

        LocalDate fromValue() {
            return from == null || from.isEmpty() ? null : LocalDate.parse(from);
        }

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

    /** A period numbering from date (yyyy-mm-dd); blank or null: off. */
    public record PeriodNumberingFromRequest(@Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String value) {

        LocalDate fromValue() {
            return value == null || value.isEmpty() ? null : LocalDate.parse(value);
        }
    }

    /** An entry date (yyyy-mm-dd); blank or null: off. */
    public record EntryDateRequest(@Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String value) {

        LocalDate entryDateValue() {
            return value == null || value.isEmpty() ? null : LocalDate.parse(value);
        }
    }

    /** A pot with at most two decimals; blank or null: none. */
    public record PotRequest(@Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String value) {

        BigDecimal potValue() {
            return value == null || value.isEmpty() ? null : new BigDecimal(value).setScale(2);
        }
    }

    /** An alert threshold with at most two decimals; blank or null: off. */
    public record AlertThresholdRequest(@Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String value) {

        BigDecimal thresholdValue() {
            return value == null || value.isEmpty() ? null : new BigDecimal(value).setScale(2);
        }
    }

    /** A parent: the id of a sheet; blank or null: none. */
    public record ParentRequest(@Size(max = 100) @Pattern(regexp = "\\s*([A-Za-z0-9._-]+)?\\s*") String value) {

        String parentValue() {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    /** Grace days: a whole number of days; blank or null: off. */
    public record GraceDaysRequest(@Pattern(regexp = "(\\d{1,4})?") String value) {

        Integer daysValue() {
            return value == null || value.isEmpty() ? null : Integer.valueOf(value);
        }
    }

    /** The fiscal year start month: 1 to 12. */
    public record FiscalStartMonthRequest(@NotNull @Pattern(regexp = "[1-9]|1[0-2]") String value) {

        int monthValue() {
            return Integer.parseInt(value);
        }
    }

    /** A grade's grace days: a whole number of days; blank or null: none; recorded from the from-date (yyyy-mm-dd), blank or null: the sheet's today. */
    public record GradeGraceRequest(@Pattern(regexp = "(\\d{1,4})?") String value,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String from) {

        LocalDate fromValue() {
            return from == null || from.isEmpty() ? null : LocalDate.parse(from);
        }

        Integer daysValue() {
            return value == null || value.isEmpty() ? null : Integer.valueOf(value);
        }
    }

    /** Terms: a whole number of days; recorded from the from-date (yyyy-mm-dd), blank or null: the sheet's today. */
    public record TermsRequest(@NotNull @Pattern(regexp = "\\d{1,4}") String value,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String from) {

        LocalDate fromValue() {
            return from == null || from.isEmpty() ? null : LocalDate.parse(from);
        }

        int termsValue() {
            return Integer.parseInt(value);
        }
    }

    /** The roles that may take an action, comma separated (clerk, steward); blank or null: the standing rule. */
    public record PermissionRequest(
            @Pattern(regexp = "(?i)\\s*((clerk|steward)\\s*(,\\s*(clerk|steward)\\s*)*)?") String value) {

        String rolesValue() {
            if (value == null || value.isBlank()) return "";
            return String.join(",", Arrays.stream(value.split(",")).map(r -> r.strip().toLowerCase()).toList());
        }
    }

    /** A role: clerk, steward or viewer. */
    public record RoleRequest(@NotNull @Pattern(regexp = "clerk|steward|viewer") String role) {

        Role roleValue() {
            return Role.valueOf(role.toUpperCase());
        }
    }

    /** A delegation of the steward role: to the user, by the steward, from and to a date, inclusive. */
    public record DelegationView(Long id, String user, String by, LocalDate from, LocalDate to) {
        static DelegationView of(Delegation d) {
            return new DelegationView(d.getId(), d.getUserName(), d.getDelegator(), d.getFromDate(), d.getToDate());
        }
    }

    /** A delegation to the user from the from-date (yyyy-mm-dd; blank or null: the sheet's today) to the to-date. */
    public record DelegationRequest(@NotBlank @Size(max = 100) String user,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String from,
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String to) {

        LocalDate fromValue() {
            return from == null || from.isEmpty() ? null : LocalDate.parse(from);
        }

        LocalDate toValue() {
            return LocalDate.parse(to);
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
                service.periodNumberingFrom(sheetId),
                service.roles(sheetId).stream().map(RoleView::of).toList(), service.gradeTerms(sheetId),
                text(service.cap(sheetId)), datedViews(service.dated(sheetId)), service.permissions(sheetId),
                service.graceDays(sheetId), service.entryDate(sheetId), text(service.pot(sheetId)),
                service.parent(sheetId), service.fiscalStartMonth(sheetId), text(service.alertThreshold(sheetId)));
    }

    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClerkLimitRequest request) {
        service.setClerkLimit(sheetId, user, request.limitValue(), request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/floor")
    public SettingsView setFloor(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FloorRequest request) {
        service.setFloor(sheetId, user, request.floorValue(), request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/rounding")
    public SettingsView setRounding(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RoundingRequest request) {
        service.setRounding(sheetId, user, request.roundingValue(), request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/terms")
    public SettingsView setTerms(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TermsRequest request) {
        service.setTerms(sheetId, user, request.termsValue(), request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/terms/{grade}")
    public SettingsView setGradeTerms(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TermsRequest request) {
        service.setGradeTerms(sheetId, user, grade, request.termsValue(), request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/late-pct")
    public SettingsView setLatePct(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid LatePctRequest request) {
        service.setLatePct(sheetId, user, request.latePctValue(), request.fromValue());
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

    @PutMapping("/settings/period-numbering-from")
    public SettingsView setPeriodNumberingFrom(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PeriodNumberingFromRequest request) {
        service.setPeriodNumberingFrom(sheetId, user, request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/grace-days")
    public SettingsView setGraceDays(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GraceDaysRequest request) {
        service.setGraceDays(sheetId, user, request.daysValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/fiscal-start-month")
    public SettingsView setFiscalStartMonth(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FiscalStartMonthRequest request) {
        service.setFiscalStartMonth(sheetId, user, request.monthValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/pot")
    public SettingsView setPot(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PotRequest request) {
        service.setPot(sheetId, user, request.potValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/alert-threshold")
    public SettingsView setAlertThreshold(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid AlertThresholdRequest request) {
        service.setAlertThreshold(sheetId, user, request.thresholdValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/parent")
    public SettingsView setParent(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ParentRequest request) {
        service.setParent(sheetId, user, request.parentValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/grace/{grade}")
    public SettingsView setGradeGrace(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GradeGraceRequest request) {
        service.setGradeGrace(sheetId, user, grade, request.daysValue(), request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/entry-date")
    public SettingsView setEntryDate(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid EntryDateRequest request) {
        service.setEntryDate(sheetId, user, request.entryDateValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/cap")
    public SettingsView setCap(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CapRequest request) {
        service.setCap(sheetId, user, request.capValue(), request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/proof-threshold")
    public SettingsView setProofThreshold(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ProofThresholdRequest request) {
        service.setProofThreshold(sheetId, user, request.thresholdValue(), request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/proof-threshold/{grade}")
    public SettingsView setGradeProofThreshold(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ProofThresholdRequest request) {
        service.setGradeProofThreshold(sheetId, user, grade, request.thresholdValue(), request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/note-threshold")
    public SettingsView setNoteThreshold(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid NoteThresholdRequest request) {
        service.setNoteThreshold(sheetId, user, request.thresholdValue(), request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/review-threshold")
    public SettingsView setReviewThreshold(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ReviewThresholdRequest request) {
        service.setReviewThreshold(sheetId, user, request.thresholdValue(), request.fromValue());
        return get(sheetId, user);
    }

    @PutMapping("/settings/perm/{action}")
    public SettingsView setPermission(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "open|settle|void|reassign|close|reopen") String action,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PermissionRequest request) {
        service.setPermission(sheetId, user, action, request.rolesValue());
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

    @GetMapping("/delegations")
    public List<DelegationView> delegations(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.delegations(sheetId).stream().map(DelegationView::of).toList();
    }

    @PostMapping("/delegations")
    public DelegationView delegate(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid DelegationRequest request) {
        return DelegationView.of(service.delegate(sheetId, user, request.user(), request.fromValue(), request.toValue()));
    }

    private static String text(BigDecimal limit) {
        return limit == null ? null : limit.setScale(2).toPlainString();
    }
}
