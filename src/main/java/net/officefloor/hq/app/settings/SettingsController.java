package net.officefloor.hq.app.settings;

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
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.sheet.DatedSetting;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class SettingsController {

    /** The sheet's settings as the screen shows them: the clerk limit with two decimals, null for
     *  none, the floor with two decimals, the terms in days, the late percent with two decimals,
     *  the policy date, null when blank, the period numbering from-date, null when blank (off),
     *  the terms each grade has of its own, a grade without them left out, the cap with two
     *  decimals, null for none, and the proof threshold with two decimals, null for none, the grace days, null when blank (off), the entry date, null when blank (off), the pot with two decimals, null for none, the alert threshold with two decimals, null for none, the month (1 to 12) the fiscal year starts in, each of the dated ones as in force on the sheet's today; and every
     *  value recorded for the dated settings, by key, each key's by from-date; and the permission
     *  matrix: the roles set for each action, comma separated, an action left blank left out; and the
     *  escalation gap in days, null when blank (off); and the review SLA in days, null when blank (none);
     *  and the mid and high score thresholds, each null when blank; and whether the score log is on;
     *  and the maximum of open claims per person, null when blank (none). */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, String cap, String proofThreshold,
            Integer graceDays, LocalDate entryDate, String pot, String alertThreshold, int fiscalStartMonth, Map<String, List<DatedValue>> dated, Map<String, String> permissions,
            Integer escalateAfter, Integer reviewSla, Integer scoreMid, Integer scoreHigh, boolean scoreLog, Integer maxOpen) {
        static SettingsView of(BigDecimal clerkLimit, BigDecimal floor, int terms, BigDecimal latePct, LocalDate policyDate,
                LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, BigDecimal cap, BigDecimal proofThreshold,
                Integer graceDays, LocalDate entryDate, BigDecimal pot, BigDecimal alertThreshold, int fiscalStartMonth, Map<String, List<DatedSetting>> dated, Map<Permission, String> permissions,
                Integer escalateAfter, Integer reviewSla, Integer scoreMid, Integer scoreHigh, boolean scoreLog, Integer maxOpen) {
            Map<String, String> perms = new TreeMap<>();
            permissions.forEach((action, roles) -> perms.put(action.key(), roles));
            return new SettingsView(clerkLimit == null ? null : clerkLimit.toPlainString(), floor.toPlainString(), terms,
                    latePct.setScale(2).toPlainString(), policyDate, periodNumberingFrom, gradeTerms,
                    cap == null ? null : cap.setScale(2).toPlainString(),
                    proofThreshold == null ? null : proofThreshold.setScale(2).toPlainString(), graceDays, entryDate,
                    pot == null ? null : pot.setScale(2).toPlainString(),
                    alertThreshold == null ? null : alertThreshold.setScale(2).toPlainString(), fiscalStartMonth, DatedValue.of(dated), perms,
                    escalateAfter, reviewSla, scoreMid, scoreHigh, scoreLog, maxOpen);
        }
    }

    /** A value recorded for a dated setting: in force from its from-date until the next one of its
     *  key; null for none. */
    public record DatedValue(LocalDate from, String value) {
        /** Every value recorded, by key, each key's by from-date. */
        public static Map<String, List<DatedValue>> of(Map<String, List<DatedSetting>> dated) {
            Map<String, List<DatedValue>> out = new TreeMap<>();
            dated.forEach((key, values) -> out.put(key,
                    values.stream().map(d -> new DatedValue(d.getFrom(), d.getValue())).toList()));
            return out;
        }
    }

    /** A from-date as a form submits it: a date as yyyy-mm-dd, blank or left out for the sheet's today. */
    private static final String FROM = "(\\d{4}-\\d{2}-\\d{2})?";

    /** The clerk limit as the form submits it: 0.00 or more with up to two decimals, blank for no
     *  limit, from a date (blank: the sheet's today). */
    public record ClerkLimitForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The floor as the form submits it: 0.00 or more with up to two decimals, blank for the
     *  default, from a date (blank: the sheet's today). */
    public record FloorForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The terms as the form submits them: a whole number of days, blank for the default, from a
     *  date (blank: the sheet's today). */
    public record TermsForm(@NotNull @Pattern(regexp = "(\\d{1,4})?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The late percent as the form submits it: 0.00 or more with up to two decimals, blank for
     *  the default, from a date (blank: the sheet's today). */
    public record LatePctForm(@NotNull @Pattern(regexp = "(\\d{1,3}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The cap as the form submits it: 0.00 or more with up to two decimals, blank for none, from
     *  a date (blank: the sheet's today). */
    public record CapForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The proof threshold as the form submits it: 0.00 or more with up to two decimals, blank
     *  for none, from a date (blank: the sheet's today). */
    public record ProofThresholdForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The note threshold as the form submits it: 0.00 or more with up to two decimals, blank
     *  for none, from a date (blank: the sheet's today). */
    public record NoteThresholdForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The review threshold as the form submits it: 0.00 or more with up to two decimals, blank
     *  for none, from a date (blank: the sheet's today). */
    public record ReviewThresholdForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The rounding mode as the form submits it: half-up or half-even, blank for none, from a date
     *  (blank: the sheet's today). */
    public record RoundingForm(@NotNull @Pattern(regexp = "(half-up|half-even)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The policy date as the form submits it: a date as yyyy-mm-dd, blank for none (the sheet's today). */
    public record PolicyDateForm(@NotNull @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String value) {
    }

    /** The period numbering from-date as the form submits it: a date as yyyy-mm-dd, blank for off. */
    public record PeriodNumberingFromForm(@NotNull @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String value) {
    }

    /** The entry date as the form submits it: a date as yyyy-mm-dd, blank for off. */
    public record EntryDateForm(@NotNull @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String value) {
    }

    /** The grace days as the form submits them: a whole number of days, blank for off. */
    public record GraceDaysForm(@NotNull @Pattern(regexp = "(\\d{1,4})?") String value) {
    }

    /** The escalation gap as the form submits it: a whole number of days, blank for off. */
    public record EscalateAfterForm(@NotNull @Pattern(regexp = "(\\d{1,4})?") String value) {
    }

    /** The review SLA as the form submits it: a whole number of days, blank for none. */
    public record ReviewSlaForm(@NotNull @Pattern(regexp = "(\\d{1,4})?") String value) {
    }

    /** The maximum of open claims per person as the form submits it: a whole number, blank for none. */
    public record MaxOpenForm(@NotNull @Pattern(regexp = "(\\d{1,4})?") String value) {
    }

    /** A score threshold as the form submits it: a whole number, blank for none. */
    public record ScoreThresholdForm(@NotNull @Pattern(regexp = "(-?\\d{1,6})?") String value) {
    }

    /** A switch as the form submits it: on, or blank for off. */
    public record SwitchForm(@NotNull @Pattern(regexp = "(on)?") String value) {
    }

    /** The fiscal year's start month as the form submits it: a month from 1 to 12. */
    public record FiscalStartMonthForm(@NotNull @Pattern(regexp = "0?[1-9]|1[0-2]") String value) {
    }

    /** The pot as the form submits it: 0.00 or more with up to two decimals, blank for none. */
    public record PotForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value) {
    }

    /** The alert threshold as the form submits it: 0.00 or more with up to two decimals, blank for off. */
    public record AlertThresholdForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value) {
    }

    /** The proof kinds as the form submits them: receipt and/or photo, comma separated, blank for none. */
    public record ProofKindsForm(@NotNull @Size(max = 100) String value) {
    }

    /** The proof kinds as they are set, comma separated; null when none. */
    public record ProofKindsView(String proofKinds) {
    }

    /** The default grade as the form submits it: one of the grades, blank for A. */
    public record DefaultGradeForm(@NotNull @Size(max = 10) String value) {
    }

    /** The default grade as it is set; null when blank (A). */
    public record DefaultGradeView(String defaultGrade) {
    }

    /** The parent as the form submits it: a sheet id without spaces, blank for none. */
    public record ParentForm(@NotNull @Size(max = 100) @Pattern(regexp = "(\\S+)?") String value) {
    }

    /** The parent sheet as it is set; null when none. */
    public record ParentView(String parent) {
    }

    /** An action's roles as the form submits them: clerk and/or steward, comma separated, blank
     *  for the standing rule. */
    public record PermissionForm(@NotNull @Size(max = 100)
            @Pattern(regexp = "(\\s*(clerk|steward)\\s*(,\\s*(clerk|steward)\\s*)*)?") String value) {
    }

    /** A user name's role on the sheet. */
    /** The role the signed-in user acts in. */
    public record MyRoleView(String role) {
    }

    public record RoleView(String user, String role) {
        static RoleView of(SheetRole r) {
            return new RoleView(r.getUser(), r.getRole().key());
        }
    }

    /** A role as the form submits it: a user name without spaces and clerk, steward or viewer. */
    public record RoleForm(
            @NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String user,
            @NotNull @Pattern(regexp = "clerk|steward|viewer|restricted") String role) {
    }

    public record DelegationView(long id, String user, String steward, LocalDate from, LocalDate to) {
        static DelegationView of(SheetDelegation d) {
            return of(d.getId(), d);
        }

        /** The delegation of the id, which a version rebuilt from the sheet's history does not carry. */
        static DelegationView of(long id, SheetDelegation d) {
            return new DelegationView(id, d.getUser(), d.getSteward(), d.getFrom(), d.getTo());
        }
    }

    /** A delegation as the form submits it: a user name without spaces, a from-date (blank: the
     *  sheet's today) and a to-date (blank: the from-date), each as yyyy-mm-dd. */
    public record DelegationForm(
            @NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String user,
            String from,
            String to) {
    }

    /** A void reason as the form submits it: text, trimmed of spaces at either end, not blank. */
    public record VoidReasonForm(@NotBlank @Size(max = 200) String reason) {
    }

    /** A holiday as the form submits it: a date as yyyy-mm-dd. */
    public record HolidayForm(@NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String date) {
    }

    /** The grades a person can hold. */
    private static final List<String> GRADES = List.of("A", "B", "C");

    private final SettingsService service;
    private final HistoryService history;

    public SettingsController(SettingsService service, HistoryService history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping("/settings")
    public SettingsView settings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return asOf == null ? view(sheetId) : viewAsOf(history.asOf(sheetId, asOf));
    }

    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClerkLimitForm body) {
        BigDecimal limit = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setClerkLimit(sheetId, user.trim(), limit, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/clerk-limit/{grade}")
    public SettingsView setGradeClerkLimit(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable @NotNull @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClerkLimitForm body) {
        BigDecimal limit = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setGradeClerkLimit(sheetId, user.trim(), grade, limit, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/floor")
    public SettingsView setFloor(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FloorForm body) {
        BigDecimal floor = body.value().isEmpty() ? Sheet.DEFAULT_FLOOR : new BigDecimal(body.value());
        service.setFloor(sheetId, user.trim(), floor, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/terms")
    public SettingsView setTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm body) {
        int terms = body.value().isEmpty() ? Sheet.DEFAULT_TERMS : Integer.parseInt(body.value());
        service.setTerms(sheetId, user.trim(), terms, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/terms/{grade}")
    public SettingsView setGradeTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable @NotNull @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm body) {
        Integer terms = body.value().isEmpty() ? null : Integer.valueOf(body.value());
        service.setGradeTerms(sheetId, user.trim(), grade, terms, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/grace/{grade}")
    public SettingsView setGradeGrace(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable @NotNull @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm body) {
        Integer grace = body.value().isEmpty() ? null : Integer.valueOf(body.value());
        service.setGradeGrace(sheetId, user.trim(), grade, grace, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/late-pct")
    public SettingsView setLatePct(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody LatePctForm body) {
        BigDecimal latePct = body.value().isEmpty() ? Sheet.DEFAULT_LATE_PCT : new BigDecimal(body.value());
        service.setLatePct(sheetId, user.trim(), latePct, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/cap")
    public SettingsView setCap(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CapForm body) {
        BigDecimal cap = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setCap(sheetId, user.trim(), cap, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/proof-threshold")
    public SettingsView setProofThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ProofThresholdForm body) {
        BigDecimal threshold = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setProofThreshold(sheetId, user.trim(), threshold, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/proof-threshold/{grade}")
    public SettingsView setGradeProofThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable @NotNull @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ProofThresholdForm body) {
        BigDecimal threshold = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setGradeProofThreshold(sheetId, user.trim(), grade, threshold, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/note-threshold")
    public SettingsView setNoteThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody NoteThresholdForm body) {
        BigDecimal threshold = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setNoteThreshold(sheetId, user.trim(), threshold, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/review-threshold")
    public SettingsView setReviewThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ReviewThresholdForm body) {
        BigDecimal threshold = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setReviewThreshold(sheetId, user.trim(), threshold, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/rounding")
    public SettingsView setRounding(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RoundingForm body) {
        service.setRounding(sheetId, user.trim(), body.value().isEmpty() ? null : body.value(), fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/policy-date")
    public SettingsView setPolicyDate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PolicyDateForm body) {
        LocalDate policyDate;
        try {
            policyDate = body.value().isEmpty() ? null : LocalDate.parse(body.value());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date");
        }
        service.setPolicyDate(sheetId, user.trim(), policyDate);
        return view(sheetId);
    }

    @PutMapping("/settings/period-numbering-from")
    public SettingsView setPeriodNumberingFrom(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PeriodNumberingFromForm body) {
        LocalDate from;
        try {
            from = body.value().isEmpty() ? null : LocalDate.parse(body.value());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date");
        }
        service.setPeriodNumberingFrom(sheetId, user.trim(), from);
        return view(sheetId);
    }

    @PutMapping("/settings/entry-date")
    public SettingsView setEntryDate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody EntryDateForm body) {
        LocalDate entryDate;
        try {
            entryDate = body.value().isEmpty() ? null : LocalDate.parse(body.value());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date");
        }
        service.setEntryDate(sheetId, user.trim(), entryDate);
        return view(sheetId);
    }

    @PutMapping("/settings/grace-days")
    public SettingsView setGraceDays(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GraceDaysForm body) {
        Integer graceDays = body.value().isEmpty() ? null : Integer.valueOf(body.value());
        service.setGraceDays(sheetId, user.trim(), graceDays);
        return view(sheetId);
    }

    @PutMapping("/settings/escalate-after")
    public SettingsView setEscalateAfter(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody EscalateAfterForm body) {
        Integer escalateAfter = body.value().isEmpty() ? null : Integer.valueOf(body.value());
        service.setEscalateAfter(sheetId, user.trim(), escalateAfter);
        return view(sheetId);
    }

    @PutMapping("/settings/review-sla")
    public SettingsView setReviewSla(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ReviewSlaForm body) {
        Integer reviewSla = body.value().isEmpty() ? null : Integer.valueOf(body.value());
        service.setReviewSla(sheetId, user.trim(), reviewSla);
        return view(sheetId);
    }

    @PutMapping("/settings/max-open")
    public SettingsView setMaxOpen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MaxOpenForm body) {
        Integer maxOpen = body.value().isEmpty() ? null : Integer.valueOf(body.value());
        service.setMaxOpen(sheetId, user.trim(), maxOpen);
        return view(sheetId);
    }

    @PutMapping("/settings/score-mid")
    public SettingsView setScoreMid(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ScoreThresholdForm body) {
        Integer scoreMid = body.value().isEmpty() ? null : Integer.valueOf(body.value());
        service.setScoreMid(sheetId, user.trim(), scoreMid);
        return view(sheetId);
    }

    @PutMapping("/settings/score-high")
    public SettingsView setScoreHigh(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ScoreThresholdForm body) {
        Integer scoreHigh = body.value().isEmpty() ? null : Integer.valueOf(body.value());
        service.setScoreHigh(sheetId, user.trim(), scoreHigh);
        return view(sheetId);
    }

    @PutMapping("/settings/score-log")
    public SettingsView setScoreLog(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody SwitchForm body) {
        service.setScoreLog(sheetId, user.trim(), !body.value().isEmpty());
        return view(sheetId);
    }

    @PutMapping("/settings/fiscal-start-month")
    public SettingsView setFiscalStartMonth(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FiscalStartMonthForm body) {
        service.setFiscalStartMonth(sheetId, user.trim(), Integer.parseInt(body.value()));
        return view(sheetId);
    }

    @PutMapping("/settings/pot")
    public SettingsView setPot(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PotForm body) {
        BigDecimal pot = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setPot(sheetId, user.trim(), pot);
        return view(sheetId);
    }

    @PutMapping("/settings/alert-threshold")
    public SettingsView setAlertThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody AlertThresholdForm body) {
        BigDecimal threshold = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setAlertThreshold(sheetId, user.trim(), threshold);
        return view(sheetId);
    }

    @PutMapping("/settings/proof-kinds")
    public ProofKindsView setProofKinds(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ProofKindsForm body) {
        return new ProofKindsView(service.setProofKinds(sheetId, user.trim(), body.value()));
    }

    @PutMapping("/settings/default-grade")
    public DefaultGradeView setDefaultGrade(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody DefaultGradeForm body) {
        return new DefaultGradeView(service.setDefaultGrade(sheetId, user.trim(), body.value()));
    }

    @PutMapping("/settings/parent")
    public ParentView setParent(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ParentForm body) {
        return new ParentView(service.setParent(sheetId, user.trim(), body.value().isEmpty() ? null : body.value()));
    }

    @PutMapping("/settings/perm/{action}")
    public SettingsView setPermission(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable @NotNull @Pattern(regexp = "open|settle|void|reassign|close|reopen") String action,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PermissionForm body) {
        String roles = String.join(",", Arrays.stream(body.value().split(","))
                .map(String::trim).filter(r -> !r.isEmpty()).toList());
        service.setPermission(sheetId, user.trim(), Permission.of(action), roles);
        return view(sheetId);
    }

    private SettingsView view(String sheetId) {
        return SettingsView.of(service.clerkLimit(sheetId), service.floor(sheetId), service.terms(sheetId),
                service.latePct(sheetId), service.policyDate(sheetId), service.periodNumberingFrom(sheetId),
                gradeTerms(sheetId), service.cap(sheetId), service.proofThreshold(sheetId), service.graceDays(sheetId), service.entryDate(sheetId), service.pot(sheetId), service.alertThreshold(sheetId), service.fiscalStartMonth(sheetId), service.datedSettings(sheetId),
                service.permissions(sheetId), service.escalateAfter(sheetId), service.reviewSla(sheetId),
                service.scoreMid(sheetId), service.scoreHigh(sheetId), service.scoreLog(sheetId), service.maxOpen(sheetId));
    }

    /** The settings as they stood at the end of a date, that date the sheet's today. */
    private static SettingsView viewAsOf(HistoryService.AsOf then) {
        Sheet sheet = then.sheet();
        Map<String, Integer> gradeTerms = new TreeMap<>();
        for (String grade : GRADES) {
            Integer terms = sheet.getGradeTerms(grade);
            if (terms != null) {
                gradeTerms.put(grade, terms);
            }
        }
        return SettingsView.of(sheet.getClerkLimit(), sheet.getFloor(), sheet.getTerms(), sheet.getLatePct(),
                sheet.getPolicyDate(), sheet.getPeriodNumberingFrom(), gradeTerms, sheet.getCap(),
                sheet.getProofThreshold(), sheet.getGraceDays(), sheet.getEntryDate(), sheet.getPot(), sheet.getAlertThreshold(), sheet.getFiscalStartMonth(), sheet.getDatedSettings(), then.permissions(),
                sheet.getEscalateAfter(), sheet.getReviewSla(), sheet.getScoreMid(), sheet.getScoreHigh(), sheet.isScoreLog(), sheet.getMaxOpen());
    }

    /** A from-date as the form submits it; null when blank or left out (the sheet's today). */
    private static LocalDate fromOf(String from) {
        if (from == null || from.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(from);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date");
        }
    }

    private Map<String, Integer> gradeTerms(String sheetId) {
        Map<String, Integer> out = new TreeMap<>();
        for (String grade : GRADES) {
            Integer terms = service.gradeTerms(sheetId, grade);
            if (terms != null) {
                out.put(grade, terms);
            }
        }
        return out;
    }

    @GetMapping("/roles")
    public List<RoleView> roles(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        List<SheetRole> roles = asOf == null ? service.roles(sheetId) : history.asOf(sheetId, asOf).roles();
        return roles.stream().map(RoleView::of).toList();
    }

    /** The role the signed-in user acts in on the sheet: under an as-of date, the role it held then
     *  (a steward while a steward's delegation to it covered that date). */
    @GetMapping("/my-role")
    public MyRoleView myRole(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        String name = user.trim();
        if (asOf == null) {
            return new MyRoleView(service.roleOf(sheetId, name).key());
        }
        HistoryService.AsOf then = history.asOf(sheetId, asOf);
        Role held = then.roles().stream().filter(r -> r.getUser().equals(name)).map(SheetRole::getRole)
                .findFirst().orElse(Role.CLERK);
        boolean delegated = then.delegations().values().stream()
                .anyMatch(d -> d.getUser().equals(name) && d.covers(asOf));
        return new MyRoleView((held != Role.STEWARD && delegated ? Role.STEWARD : held).key());
    }

    @PutMapping("/roles")
    public RoleView setRole(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RoleForm body) {
        return RoleView.of(service.setRole(sheetId, user.trim(), body.user().trim(), Role.of(body.role())));
    }

    @GetMapping("/delegations")
    public List<DelegationView> delegations(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.asOf(sheetId, asOf).delegations().entrySet().stream()
                    .map(e -> DelegationView.of(e.getKey(), e.getValue())).toList();
        }
        return service.delegations(sheetId).stream().map(DelegationView::of).toList();
    }

    @PostMapping("/delegations")
    public DelegationView delegate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody DelegationForm body) {
        return DelegationView.of(service.delegate(sheetId, user.trim(), body.user().trim(),
                fromOf(body.from()), fromOf(body.to())));
    }

    @GetMapping("/holidays")
    public List<LocalDate> holidays(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return service.holidays(sheetId);
    }

    @PostMapping("/holidays")
    public LocalDate addHoliday(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody HolidayForm body) {
        return service.addHoliday(sheetId, user.trim(), fromOf(body.date().trim()));
    }

    @GetMapping("/void-reasons")
    public List<String> voidReasons(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return service.voidReasons(sheetId);
    }

    @PostMapping("/void-reasons")
    public List<String> addVoidReason(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody VoidReasonForm body) {
        service.addVoidReason(sheetId, user.trim(), body.reason().trim());
        return service.voidReasons(sheetId);
    }
}
