package net.officefloor.hq.app.sheet;

import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.history.AsOfGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.SignedIn;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sheets")
@Validated
public class SheetController {

    /** What the screen is told about a loaded sheet. */
    public record SheetView(String id, LocalDate today, String floor, int terms, String latePct, LocalDate policyDate,
            String cap, List<DatedValue> floorHistory, List<DatedValue> latePctHistory, boolean archived,
            Map<String, List<DatedValue>> graceHistory, Integer lastSettleBatch, String pot, String parent,
            int fiscalStartMonth, List<DatedValue> roundingHistory, Integer reviewSla) {

        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.getFloor().toPlainString(), sheet.getTerms(),
                    sheet.getLatePct().toPlainString(), sheet.getPolicyDate(),
                    sheet.getCap() == null ? null : sheet.getCap().toPlainString(),
                    DatedValue.of(sheet.floorHistory()), DatedValue.of(sheet.latePctHistory()), sheet.isArchived(),
                    graceHistory(sheet), sheet.getLastSettleBatch() == 0 ? null : sheet.getLastSettleBatch(),
                    sheet.getPot() == null ? null : sheet.getPot().toPlainString(), sheet.getParentId(),
                    sheet.getFiscalStartMonth(), DatedValue.of(sheet.roundingHistory()), sheet.getReviewSla());
        }

        /** Each grade's grace values by from-date, its starting value (none) first. */
        static Map<String, List<DatedValue>> graceHistory(Sheet sheet) {
            Map<String, List<DatedValue>> all = new TreeMap<>();
            for (String grade : GRADES) {
                all.put(grade, DatedValue.of(sheet.gradeGraceHistory(grade)));
            }
            return all;
        }
    }

    /** The grades a person can have. */
    static final List<String> GRADES = List.of("A", "B", "C");

    /** A change to the sheet's today. */
    public record TodayChange(@NotNull LocalDate today) {
    }

    /** What the screen is told about the sheet's settings, each dated one as in force on the sheet's today
     * (with the floor's and the late percent's values by from-date): the clerk limit with two decimals, null for none,
     * the floor with two decimals, the terms in days, the late percent with two decimals, and the policy
     * date, null for none (the sheet's today), and the period-numbering-from date, null for none (off), and the grace days, null for none (off), and
     * each grade's own terms in days, null for none (the sheet's terms), and the cap with two decimals, null
     * for none, and the proof threshold with two decimals, null for none, and the note threshold with two
     * decimals, null for none, and the entry date, null for none (off), and each grade's grace in days,
     * null for none, with each grade's grace values by from-date, and each grade's proof threshold with two
     * decimals, null for none (the sheet's applies), and the pot with two decimals, null for none, and the
     * parent sheet's id, null for none, and the month, 1 to 12, the fiscal year starts in, and the alert
     * threshold with two decimals, null for off, and the rounding mode (half-up or half-even), null for none
     * (the policy date decides), with its values by from-date, and the review threshold with two decimals,
     * null for none, and the escalation gap in days, null for off, and the review SLA in days, null for none. */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            LocalDate periodNumberingFrom, Integer graceDays, Integer termsA, Integer termsB, Integer termsC, String cap,
            List<DatedValue> floorHistory, List<DatedValue> latePctHistory, String proofThreshold,
            Map<String, String> permissions, String noteThreshold, LocalDate entryDate, Integer graceA, Integer graceB,
            Integer graceC, Map<String, List<DatedValue>> graceHistory, Map<String, String> gradeProofThresholds,
            String pot, String parent, int fiscalStartMonth, String alertThreshold, String rounding,
            List<DatedValue> roundingHistory, String reviewThreshold, Integer escalateAfter, Integer reviewSla) {

        static SettingsView of(Sheet sheet) {
            return new SettingsView(sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.getFloor().toPlainString(), sheet.getTerms(), sheet.getLatePct().toPlainString(),
                    sheet.getPolicyDate(), sheet.getPeriodNumberingFrom(), sheet.getGraceDays(), sheet.getGradeTerms("A"),
                    sheet.getGradeTerms("B"), sheet.getGradeTerms("C"),
                    sheet.getCap() == null ? null : sheet.getCap().toPlainString(),
                    DatedValue.of(sheet.floorHistory()), DatedValue.of(sheet.latePctHistory()),
                    sheet.getProofThreshold() == null ? null : sheet.getProofThreshold().toPlainString(),
                    permissions(sheet),
                    sheet.getNoteThreshold() == null ? null : sheet.getNoteThreshold().toPlainString(),
                    sheet.getEntryDate(), sheet.getGradeGrace("A"), sheet.getGradeGrace("B"), sheet.getGradeGrace("C"),
                    SheetView.graceHistory(sheet), gradeProofThresholds(sheet),
                    sheet.getPot() == null ? null : sheet.getPot().toPlainString(), sheet.getParentId(),
                    sheet.getFiscalStartMonth(),
                    sheet.getAlertThreshold() == null ? null : sheet.getAlertThreshold().toPlainString(),
                    sheet.getRounding(), DatedValue.of(sheet.roundingHistory()),
                    sheet.getReviewThreshold() == null ? null : sheet.getReviewThreshold().toPlainString(),
                    sheet.getEscalateAfter(), sheet.getReviewSla());
        }

        /** Each grade's proof threshold in force with two decimals; null when it has none (the sheet's applies). */
        private static Map<String, String> gradeProofThresholds(Sheet sheet) {
            Map<String, String> all = new TreeMap<>();
            for (String grade : GRADES) {
                BigDecimal threshold = sheet.getGradeProofThreshold(grade);
                all.put(grade, threshold == null ? null : threshold.toPlainString());
            }
            return all;
        }

        /** The roles set for each action of the permission matrix; blank when it keeps its standing rule. */
        private static Map<String, String> permissions(Sheet sheet) {
            Map<String, String> all = new TreeMap<>();
            Sheet.STANDING_PERMISSIONS.keySet().forEach(action -> {
                String roles = sheet.getPermission(action);
                all.put(action, roles == null ? "" : roles);
            });
            return all;
        }
    }

    /** A setting's value as entered: up to two decimals, or blank (or null) for none. */
    public record SettingChange(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) implements Dated {
    }

    /** The pot as entered: up to two decimals, or blank (or null) for none. */
    public record PotChange(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value) {

        /** The pot entered; null when blank. */
        BigDecimal pot() {
            String text = value == null ? "" : value.trim();
            return text.isEmpty() ? null : new BigDecimal(text);
        }
    }

    /** The alert threshold as entered: up to two decimals, or blank (or null) for off. */
    public record AlertThresholdChange(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value) {

        /** The threshold entered; null when blank. */
        BigDecimal threshold() {
            String text = value == null ? "" : value.trim();
            return text.isEmpty() ? null : new BigDecimal(text);
        }
    }

    /** A parent sheet's id as entered, or blank (or null) for none. */
    public record ParentChange(@Size(max = 64) @Pattern(regexp = "\\s*([A-Za-z0-9._-]+)?\\s*") String value) {

        /** The parent sheet's id entered; null when blank. */
        String parent() {
            String text = value == null ? "" : value.trim();
            return text.isEmpty() ? null : text;
        }
    }

    /** A fiscal start month as entered: a whole month number, 1 to 12; it cannot be blank. */
    public record FiscalStartMonthChange(@NotNull @Pattern(regexp = "\\s*([1-9]|1[0-2])\\s*") String value) {

        /** The month entered. */
        int month() {
            return Integer.parseInt(value.trim());
        }
    }

    /** Grace days as entered: a whole number of days, or blank (or null) for off. */
    public record GraceDaysChange(@Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String value) {

        /** The days entered; null when blank. */
        Integer days() {
            String text = value == null ? "" : value.trim();
            return text.isEmpty() ? null : Integer.valueOf(text);
        }
    }

    /** The roles that may take an action as entered: clerk or steward, comma separated, or blank for the standing rule. */
    public record PermissionChange(
            @Size(max = 64) @Pattern(regexp = "\\s*((clerk|steward)\\s*(,\\s*(clerk|steward)\\s*)*)?") String value) {

        /** The roles entered, each once, comma separated; null when blank. */
        String roles() {
            String text = value == null ? "" : value.trim();
            return text.isEmpty() ? null
                    : String.join(",", Arrays.stream(text.split(",")).map(String::trim).distinct().toList());
        }
    }

    /** A floor as entered: up to two decimals; it cannot be blank. */
    public record FloorChange(@NotNull @Pattern(regexp = "\\s*\\d{1,12}(\\.\\d{1,2})?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) implements Dated {
    }

    /** A rounding mode as entered: half-up or half-even, or blank (or null) for none. */
    public record RoundingChange(@Pattern(regexp = "\\s*(half-up|half-even)?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) implements Dated {
    }

    /** Terms as entered: a whole number of days; they cannot be blank. */
    public record TermsChange(@NotNull @Pattern(regexp = "\\s*\\d{1,4}\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) implements Dated {
    }

    /** A grade's terms as entered: a whole number of days, or blank (or null) for the sheet's terms. */
    public record GradeTermsChange(@Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) implements Dated {
    }

    /** A grade's grace as entered: a whole number of days, or blank (or null) for none. */
    public record GradeGraceChange(@Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) implements Dated {
    }

    /** A late percent as entered: 0.00 or more, up to two decimals; it cannot be blank. */
    public record LatePctChange(@NotNull @Pattern(regexp = "\\s*\\d{1,3}(\\.\\d{1,2})?\\s*") String value,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from) implements Dated {
    }

    /** A change to a dated setting: from a date entered as yyyy-mm-dd, or blank (or null) for the sheet's today. */
    interface Dated {

        String from();

        /** The from-date entered; null when blank. */
        default LocalDate fromDate() {
            return parse(from());
        }
    }

    /** A setting's value from a date: from null for its starting value, before every from-date. */
    public record DatedValue(LocalDate from, String value) {

        static List<DatedValue> of(List<DatedSetting> history) {
            return history.stream().map(d -> new DatedValue(d.getFromDate(), d.getValue())).toList();
        }
    }

    /** A date as entered; null when blank. */
    static LocalDate parse(String value) {
        String text = value == null ? "" : value.trim();
        try {
            return text.isEmpty() ? null : LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + text);
        }
    }

    /** A date setting as entered: yyyy-mm-dd, or blank (or null) for none. */
    public record DateChange(@Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String value) {

        /** The date entered; null when blank. */
        LocalDate date() {
            return parse(value);
        }
    }

    /** The id of a sheet to clone this one to, as entered. */
    public record CloneRequest(@NotBlank @Size(max = 64) @Pattern(regexp = "\\s*[A-Za-z0-9._-]+\\s*") String to) {
    }

    /** What the screen is told about a clone made: the id it was made at. */
    public record CloneView(String to) {
    }

    private final SheetService service;
    private final RoleService roles;
    private final SheetHistory history;
    private final CloneService clones;

    public SheetController(SheetService service, RoleService roles, SheetHistory history, CloneService clones) {
        this.service = service;
        this.roles = roles;
        this.history = history;
        this.clones = clones;
    }

    /**
     * Load a sheet by id, starting an empty one if the id was never used; with an as-of date, the sheet
     * as it stood at the end of that date, with that date as its today.
     */
    @PostMapping("/{id}/load")
    public SheetView load(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) String asOf) {
        return SheetView.of(history.sheet(service.load(id), AsOfGuard.date(asOf)));
    }

    /** Change the sheet's today. */
    @PutMapping("/{id}/today")
    public SheetView setToday(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid TodayChange change) {
        return SheetView.of(service.setToday(id, SignedIn.name(user), change.today()));
    }

    /** Archive the sheet: only a steward may. */
    @PostMapping("/{id}/archive")
    public SheetView archive(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        String name = SignedIn.name(user);
        if (!Role.STEWARD.equals(roles.roleOf(id, name))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may archive the sheet.");
        }
        return SheetView.of(service.archive(id, name));
    }

    /** Clone the sheet to another id, which must never have been used: only a steward may. */
    @PostMapping("/{id}/clone")
    public CloneView cloneTo(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid CloneRequest request) {
        String to = request.to().trim();
        clones.cloneTo(id, SignedIn.name(user), to);
        return new CloneView(to);
    }

    /** The sheet's settings; with an as-of date, as they stood at the end of that date. */
    @GetMapping("/{id}/settings")
    public SettingsView settings(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) String asOf) {
        return SettingsView.of(history.sheet(service.load(id), AsOfGuard.date(asOf)));
    }

    /** Record the sheet's clerk limit from a date (blank: the sheet's today); a blank value means no limit. */
    @PutMapping("/{id}/settings/clerk-limit")
    public SettingsView setClerkLimit(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid SettingChange change) {
        String value = change.value() == null ? "" : change.value().trim();
        return SettingsView.of(service.setClerkLimit(id, SignedIn.name(user),
                value.isEmpty() ? null : new BigDecimal(value), change.fromDate()));
    }

    /** Record the sheet's cap from a date (blank: the sheet's today); a blank value means no cap. */
    @PutMapping("/{id}/settings/cap")
    public SettingsView setCap(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid SettingChange change) {
        String value = change.value() == null ? "" : change.value().trim();
        return SettingsView.of(service.setCap(id, SignedIn.name(user), value.isEmpty() ? null : new BigDecimal(value), change.fromDate()));
    }

    /** Record the sheet's proof threshold from a date (blank: the sheet's today); a blank value means none. */
    @PutMapping("/{id}/settings/proof-threshold")
    public SettingsView setProofThreshold(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid SettingChange change) {
        String value = change.value() == null ? "" : change.value().trim();
        return SettingsView.of(service.setProofThreshold(id, SignedIn.name(user),
                value.isEmpty() ? null : new BigDecimal(value), change.fromDate()));
    }

    /** Record a grade's proof threshold from a date (blank: the sheet's today); a blank value means none (the sheet's applies). */
    @PutMapping("/{id}/settings/proof-threshold-{grade}")
    public SettingsView setGradeProofThreshold(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid SettingChange change) {
        String value = change.value() == null ? "" : change.value().trim();
        return SettingsView.of(service.setGradeProofThreshold(id, SignedIn.name(user), grade,
                value.isEmpty() ? null : new BigDecimal(value), change.fromDate()));
    }

    /** Record the sheet's note threshold from a date (blank: the sheet's today); a blank value means none. */
    @PutMapping("/{id}/settings/note-threshold")
    public SettingsView setNoteThreshold(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid SettingChange change) {
        String value = change.value() == null ? "" : change.value().trim();
        return SettingsView.of(service.setNoteThreshold(id, SignedIn.name(user),
                value.isEmpty() ? null : new BigDecimal(value), change.fromDate()));
    }

    /** Record the sheet's review threshold from a date (blank: the sheet's today); a blank value means none. */
    @PutMapping("/{id}/settings/review-threshold")
    public SettingsView setReviewThreshold(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid SettingChange change) {
        String value = change.value() == null ? "" : change.value().trim();
        return SettingsView.of(service.setReviewThreshold(id, SignedIn.name(user),
                value.isEmpty() ? null : new BigDecimal(value), change.fromDate()));
    }

    /** Record the sheet's floor from a date (blank: the sheet's today). */
    @PutMapping("/{id}/settings/floor")
    public SettingsView setFloor(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid FloorChange change) {
        return SettingsView.of(service.setFloor(id, SignedIn.name(user), new BigDecimal(change.value().trim()), change.fromDate()));
    }

    /** Record the sheet's rounding mode from a date (blank: the sheet's today); a blank value means none. */
    @PutMapping("/{id}/settings/rounding")
    public SettingsView setRounding(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid RoundingChange change) {
        String value = change.value() == null ? "" : change.value().trim();
        return SettingsView.of(service.setRounding(id, SignedIn.name(user), value.isEmpty() ? null : value, change.fromDate()));
    }

    /** Record the sheet's terms, in days, from a date (blank: the sheet's today). */
    @PutMapping("/{id}/settings/terms")
    public SettingsView setTerms(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid TermsChange change) {
        return SettingsView.of(service.setTerms(id, SignedIn.name(user), Integer.parseInt(change.value().trim()), change.fromDate()));
    }

    /** Record a grade's own terms, in days, from a date (blank: the sheet's today); a blank value means the grade uses the sheet's terms. */
    @PutMapping("/{id}/settings/terms-{grade}")
    public SettingsView setGradeTerms(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GradeTermsChange change) {
        String value = change.value() == null ? "" : change.value().trim();
        return SettingsView.of(service.setGradeTerms(id, SignedIn.name(user), grade,
                value.isEmpty() ? null : Integer.valueOf(value), change.fromDate()));
    }

    /** Record a grade's grace, in days, from a date (blank: the sheet's today); a blank value means none. */
    @PutMapping("/{id}/settings/grace-{grade}")
    public SettingsView setGradeGrace(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GradeGraceChange change) {
        String value = change.value() == null ? "" : change.value().trim();
        return SettingsView.of(service.setGradeGrace(id, SignedIn.name(user), grade,
                value.isEmpty() ? null : Integer.valueOf(value), change.fromDate()));
    }

    /** Record the sheet's late percent from a date (blank: the sheet's today). */
    @PutMapping("/{id}/settings/late-pct")
    public SettingsView setLatePct(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid LatePctChange change) {
        return SettingsView.of(service.setLatePct(id, SignedIn.name(user), new BigDecimal(change.value().trim()), change.fromDate()));
    }

    /** Change the sheet's policy date; a blank value means the sheet's today. */
    @PutMapping("/{id}/settings/policy-date")
    public SettingsView setPolicyDate(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid DateChange change) {
        return SettingsView.of(service.setPolicyDate(id, SignedIn.name(user), change.date()));
    }

    /** Change the sheet's period-numbering-from date; a blank value turns period numbering off. */
    @PutMapping("/{id}/settings/period-numbering-from")
    public SettingsView setPeriodNumberingFrom(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid DateChange change) {
        return SettingsView.of(service.setPeriodNumberingFrom(id, SignedIn.name(user), change.date()));
    }

    /** Change the sheet's entry date; a blank value turns it off (new claims are dated the sheet's today). */
    @PutMapping("/{id}/settings/entry-date")
    public SettingsView setEntryDate(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid DateChange change) {
        return SettingsView.of(service.setEntryDate(id, SignedIn.name(user), change.date()));
    }

    /** Change the sheet's grace days; a blank value turns closing periods by themselves off. */
    @PutMapping("/{id}/settings/grace-days")
    public SettingsView setGraceDays(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GraceDaysChange change) {
        return SettingsView.of(service.setGraceDays(id, SignedIn.name(user), change.days()));
    }

    /** Change the sheet's escalation gap; a blank value turns escalating reminders off. */
    @PutMapping("/{id}/settings/escalate-after")
    public SettingsView setEscalateAfter(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GraceDaysChange change) {
        return SettingsView.of(service.setEscalateAfter(id, SignedIn.name(user), change.days()));
    }

    /** Change the sheet's review SLA; a blank value means none. */
    @PutMapping("/{id}/settings/review-sla")
    public SettingsView setReviewSla(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GraceDaysChange change) {
        return SettingsView.of(service.setReviewSla(id, SignedIn.name(user), change.days()));
    }

    /** Change the month the sheet's fiscal year starts in. */
    @PutMapping("/{id}/settings/fiscal-start-month")
    public SettingsView setFiscalStartMonth(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid FiscalStartMonthChange change) {
        return SettingsView.of(service.setFiscalStartMonth(id, SignedIn.name(user), change.month()));
    }

    /** Change the sheet's pot; a blank value means none. */
    @PutMapping("/{id}/settings/pot")
    public SettingsView setPot(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PotChange change) {
        return SettingsView.of(service.setPot(id, SignedIn.name(user), change.pot()));
    }

    /** Change the sheet's alert threshold; a blank value means off. */
    @PutMapping("/{id}/settings/alert-threshold")
    public SettingsView setAlertThreshold(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid AlertThresholdChange change) {
        return SettingsView.of(service.setAlertThreshold(id, SignedIn.name(user), change.threshold()));
    }

    /** Change the sheet's parent; a blank value means none. */
    @PutMapping("/{id}/settings/parent")
    public SettingsView setParent(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ParentChange change) {
        return SettingsView.of(service.setParent(id, SignedIn.name(user), change.parent()));
    }

    /** Set the roles that may take an action of the permission matrix; a blank value keeps its standing rule. */
    @PutMapping("/{id}/settings/perm-{action}")
    public SettingsView setPermission(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @PathVariable @Pattern(regexp = "open|settle|void|reassign|close|reopen") String action,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PermissionChange change) {
        return SettingsView.of(service.setPermission(id, SignedIn.name(user), action, change.roles()));
    }
}
