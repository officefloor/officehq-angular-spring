package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.person.Grade;
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
public class SettingController {

    /** What the screen shows of the sheet's settings; the clerk limit has two decimals, or is null
     *  when there is no limit; the floor has two decimals; the terms are the business days after a claim's date
     *  it is due by; the late percent has two decimals; the policy date is null when blank (the sheet's
     *  today then); period numbering from is null when blank (sheet-wide numbering); grade terms are
     *  the terms each grade set for itself, by grade, a grade without its own using the sheet's; the cap
     *  has two decimals, or is null when there is no cap; so has the proof threshold, null when there is none,
     *  and the note threshold, null when there is none;
     *  permissions holds, by action, the roles that may take it as set (blank: the standing rule);
     *  grace days are the days after an open period's to-date before a change of today closes it, null when blank (off);
     *  the entry date is the date a new claim given none takes, null when blank (the sheet's today then); the pot has two decimals, or is null when there is none (not dated); the parent is the sheet whose rates are used where this one has none, null when none (not dated); the fiscal start month is the month, 1 to 12, each fiscal year starts on (not dated); the alert threshold has two decimals, or is null when off (not dated); the escalation gap is whole days, or null when off (not dated); the review SLA is whole days, or null when none (not dated). Each of these figures is the one in force on the
     *  sheet's today; dated holds, by key, every value the dated settings take from their from-dates;
     *  delegations are the stewards' delegations of their role, in the order they were made. */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            List<RoleView> roles, LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, String cap,
            Map<String, List<DatedView>> dated, String proofThreshold, Map<String, String> permissions,
            Integer graceDays, List<DelegationView> delegations, String noteThreshold, LocalDate entryDate,
            String pot, String parent, int fiscalStartMonth, String alertThreshold, Integer escalateAfter,
            Integer reviewSla) {
    }

    /** A steward's delegation of the steward role to a user, from a date to a date, inclusive. */
    public record DelegationView(long id, String user, LocalDate from, LocalDate to, String by) {
        static DelegationView of(Delegation d) {
            return new DelegationView(d.getId(), d.getUserName(), d.getFrom(), d.getTo(), d.getDelegatedBy());
        }
    }

    /** A delegation as entered on the form; no from-date means the sheet's today. */
    public record DelegateForm(@NotBlank @Size(max = 200) String user, LocalDate from, @NotNull LocalDate to) {
    }

    /** A value a dated setting takes from a date; blank when the setting is cleared from then. */
    public record DatedView(LocalDate from, String value) {
    }

    /** A role the sheet assigns a user name. */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole role) {
            return new RoleView(role.getUserName(), role.getRole().label());
        }
    }

    /** The clerk limit as entered; none means no limit. */
    public record ClerkLimitForm(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value, LocalDate from) {
    }

    /** The cap as entered; none means no cap. */
    public record CapForm(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value, LocalDate from) {
    }

    /** The proof threshold as entered; none means no threshold. */
    public record ProofThresholdForm(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /** A grade's own proof threshold as entered; none means the sheet's. */
    public record GradeProofThresholdForm(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /** The note threshold as entered; none means no threshold. */
    public record NoteThresholdForm(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /** The review threshold as entered; none means no threshold. */
    public record ReviewThresholdForm(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /** The floor as entered. */
    public record FloorForm(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /** The rounding mode as entered: half-up or half-even; none means none (the policy date decides). */
    public record RoundingForm(@Size(max = 20) String value, LocalDate from) {
    }

    /** The terms as entered: whole business days. */
    public record TermsForm(@NotNull @Min(0) @Max(36500) Integer value, LocalDate from) {
    }

    /** A grade's own terms as entered: whole business days; none means the sheet's terms. */
    public record GradeTermsForm(@Min(0) @Max(36500) Integer value, LocalDate from) {
    }

    /** A grade's grace days as entered: whole days; none means no grace. */
    public record GradeGraceForm(@Min(0) @Max(36500) Integer value, LocalDate from) {
    }

    /** The late percent as entered. */
    public record LatePctForm(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /** The policy date as entered; none means the sheet's today. */
    public record PolicyDateForm(LocalDate value) {
    }

    /** The date to number claims within their periods from, as entered; none means off. */
    public record PeriodNumberingFromForm(LocalDate value) {
    }

    /** The entry date as entered; none means off (the sheet's today). */
    public record EntryDateForm(LocalDate value) {
    }

    /** The grace days as entered: whole days; none means off. */
    public record GraceDaysForm(@Min(0) @Max(36500) Integer value) {
    }

    /** The pot as entered; none means no pot. */
    public record PotForm(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value) {
    }

    /** The alert threshold as entered; none means off. */
    public record AlertThresholdForm(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value) {
    }

    /** The escalation gap as entered: whole days; none means off. */
    public record EscalateAfterForm(@Min(0) @Max(36500) Integer value) {
    }

    /** The review SLA as entered: whole days; none means none. */
    public record ReviewSlaForm(@Min(0) @Max(36500) Integer value) {
    }

    /** The fiscal year start month as entered: 1 to 12. */
    public record FiscalStartMonthForm(@NotNull @Min(1) @Max(12) Integer value) {
    }

    /** The parent sheet's id as entered; blank means none. */
    public record ParentForm(@Size(max = 100) String value) {
    }

    /** The roles that may take an action as entered, comma separated; blank keeps the standing rule. */
    public record PermissionForm(@Size(max = 200) String value) {
    }

    /** A role to assign a user name, as entered on the form. */
    public record RoleForm(@NotBlank @Size(max = 200) String user, @NotNull String role) {
    }

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public SettingsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return view(sheetId.trim());
    }

    /** Set the sheet's clerk limit, or clear it. */
    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ClerkLimitForm form) {
        String id = sheetId.trim();
        service.setClerkLimit(id, SignedInUser.name(user), form.value(), form.from());
        return view(id);
    }

    /** Set the sheet's floor: the least a claim's live due can be. */
    @PutMapping("/settings/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid FloorForm form) {
        String id = sheetId.trim();
        service.setFloor(id, SignedInUser.name(user), form.value(), form.from());
        return view(id);
    }

    /** Set the sheet's rounding mode, or clear it: a claim dated while one is in force rounds its live figures by it. */
    @PutMapping("/settings/rounding")
    public SettingsView setRounding(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid RoundingForm form) {
        String id = sheetId.trim();
        service.setRounding(id, SignedInUser.name(user), form.value(), form.from());
        return view(id);
    }

    /** Set the sheet's terms: the business days after a claim's date it is due by. */
    @PutMapping("/settings/terms")
    public SettingsView setTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid TermsForm form) {
        String id = sheetId.trim();
        service.setTerms(id, SignedInUser.name(user), form.value(), form.from());
        return view(id);
    }

    /** Set a grade's own terms, or clear them to use the sheet's. */
    @PutMapping("/settings/terms/{grade}")
    public SettingsView setGradeTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable String grade, @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Valid GradeTermsForm form) {
        String id = sheetId.trim();
        Grade g = Arrays.stream(Grade.values()).filter((v) -> v.name().equals(grade)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such grade."));
        service.setGradeTerms(id, SignedInUser.name(user), g, form.value(), form.from());
        return view(id);
    }

    /** Set a grade's grace days, or clear them. */
    @PutMapping("/settings/grace/{grade}")
    public SettingsView setGradeGrace(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable String grade, @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Valid GradeGraceForm form) {
        String id = sheetId.trim();
        Grade g = Arrays.stream(Grade.values()).filter((v) -> v.name().equals(grade)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such grade."));
        service.setGradeGrace(id, SignedInUser.name(user), g, form.value(), form.from());
        return view(id);
    }

    /** Set the sheet's late percent: what an overdue claim gains of its figure for every full 30 days overdue. */
    @PutMapping("/settings/late-pct")
    public SettingsView setLatePct(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid LatePctForm form) {
        String id = sheetId.trim();
        service.setLatePct(id, SignedInUser.name(user), form.value(), form.from());
        return view(id);
    }

    /** Set the sheet's policy date, or clear it: a claim dated before it rounds half up, on or after it half to even. */
    @PutMapping("/settings/policy-date")
    public SettingsView setPolicyDate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PolicyDateForm form) {
        String id = sheetId.trim();
        service.setPolicyDate(id, SignedInUser.name(user), form.value());
        return view(id);
    }

    /** Set the date the sheet numbers claims within their periods from, or clear it. */
    @PutMapping("/settings/period-numbering-from")
    public SettingsView setPeriodNumberingFrom(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PeriodNumberingFromForm form) {
        String id = sheetId.trim();
        service.setPeriodNumberingFrom(id, SignedInUser.name(user), form.value());
        return view(id);
    }

    /** Set the sheet's cap: the most a person's due-total can be; or clear it. */
    @PutMapping("/settings/cap")
    public SettingsView setCap(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid CapForm form) {
        String id = sheetId.trim();
        service.setCap(id, SignedInUser.name(user), form.value(), form.from());
        return view(id);
    }

    /** Set the sheet's proof threshold, or clear it. */
    @PutMapping("/settings/proof-threshold")
    public SettingsView setProofThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ProofThresholdForm form) {
        String id = sheetId.trim();
        service.setProofThreshold(id, SignedInUser.name(user), form.value(), form.from());
        return view(id);
    }

    /** Set a grade's own proof threshold, or clear it to use the sheet's. */
    @PutMapping("/settings/proof-threshold/{grade}")
    public SettingsView setGradeProofThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable String grade, @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Valid GradeProofThresholdForm form) {
        String id = sheetId.trim();
        Grade g = Arrays.stream(Grade.values()).filter((v) -> v.name().equals(grade)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such grade."));
        service.setGradeProofThreshold(id, SignedInUser.name(user), g, form.value(), form.from());
        return view(id);
    }

    /** Set the sheet's note threshold, or clear it. */
    @PutMapping("/settings/note-threshold")
    public SettingsView setNoteThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid NoteThresholdForm form) {
        String id = sheetId.trim();
        service.setNoteThreshold(id, SignedInUser.name(user), form.value(), form.from());
        return view(id);
    }

    /** Set the sheet's review threshold, or clear it. */
    @PutMapping("/settings/review-threshold")
    public SettingsView setReviewThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ReviewThresholdForm form) {
        String id = sheetId.trim();
        service.setReviewThreshold(id, SignedInUser.name(user), form.value(), form.from());
        return view(id);
    }

    /** Set the sheet's grace days, or clear them to turn the closing of periods off. */
    @PutMapping("/settings/grace-days")
    public SettingsView setGraceDays(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid GraceDaysForm form) {
        String id = sheetId.trim();
        service.setGraceDays(id, SignedInUser.name(user), form.value());
        return view(id);
    }

    /** Set the sheet's entry date, or clear it: the date a new claim given none takes. */
    @PutMapping("/settings/entry-date")
    public SettingsView setEntryDate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid EntryDateForm form) {
        String id = sheetId.trim();
        service.setEntryDate(id, SignedInUser.name(user), form.value());
        return view(id);
    }

    /** Set the sheet's pot, or clear it: what a balanced pool shares out among its members. */
    @PutMapping("/settings/pot")
    public SettingsView setPot(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PotForm form) {
        String id = sheetId.trim();
        service.setPot(id, SignedInUser.name(user), form.value());
        return view(id);
    }

    /** Set the sheet's alert threshold, or clear it to turn alerts off. */
    @PutMapping("/settings/alert-threshold")
    public SettingsView setAlertThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid AlertThresholdForm form) {
        String id = sheetId.trim();
        service.setAlertThreshold(id, SignedInUser.name(user), form.value());
        return view(id);
    }

    /** Set the sheet's escalation gap, or clear it to turn escalation off. */
    @PutMapping("/settings/escalate-after")
    public SettingsView setEscalateAfter(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid EscalateAfterForm form) {
        String id = sheetId.trim();
        service.setEscalateAfter(id, SignedInUser.name(user), form.value());
        return view(id);
    }

    /** Set the sheet's review SLA, or clear it to have none. */
    @PutMapping("/settings/review-sla")
    public SettingsView setReviewSla(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ReviewSlaForm form) {
        String id = sheetId.trim();
        service.setReviewSla(id, SignedInUser.name(user), form.value());
        return view(id);
    }

    /** Set the month the sheet's fiscal years start on. */
    @PutMapping("/settings/fiscal-start-month")
    public SettingsView setFiscalStartMonth(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid FiscalStartMonthForm form) {
        String id = sheetId.trim();
        service.setFiscalStartMonth(id, SignedInUser.name(user), form.value());
        return view(id);
    }

    /** Set the sheet's parent, or clear it: the sheet whose rates are used where this one has none. */
    @PutMapping("/settings/parent")
    public SettingsView setParent(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ParentForm form) {
        String id = sheetId.trim();
        service.setParent(id, SignedInUser.name(user), form.value());
        return view(id);
    }

    /** Set the roles that may take an action, or clear them to keep the standing rule. */
    @PutMapping("/settings/perm/{action}")
    public SettingsView setPermission(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable String action, @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Valid PermissionForm form) {
        String id = sheetId.trim();
        service.setPermission(id, SignedInUser.name(user), action, form.value());
        return view(id);
    }

    /** Assign a user name a role on the sheet. */
    @PutMapping("/roles")
    public RoleView setRole(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid RoleForm form) {
        Role role = Role.of(form.role().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such role."));
        return RoleView.of(service.setRole(sheetId.trim(), SignedInUser.name(user), form.user().trim(), role));
    }

    /** Delegate the steward role to a user from a date to a date, inclusive; refused to all but a steward. */
    @PostMapping("/delegations")
    public DelegationView delegate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid DelegateForm form) {
        return DelegationView.of(service.delegate(sheetId.trim(), SignedInUser.name(user), form.user().trim(),
                form.from(), form.to()));
    }

    /** The settings in force on the sheet's today, with every dated value recorded. */
    private SettingsView view(String sheetId) {
        LocalDate today = service.today(sheetId);
        DatedSettings dated = service.dated(sheetId);
        Map<String, Integer> gradeTerms = new LinkedHashMap<>();
        for (Grade grade : Grade.values()) {
            Integer own = dated.gradeTermsOn(grade, today);
            if (own != null) gradeTerms.put(grade.name(), own);
        }
        Map<String, List<DatedView>> history = new LinkedHashMap<>();
        for (String key : dated.keys()) {
            history.put(key, dated.history(key).stream().map((s) -> new DatedView(s.getFrom(), s.getValue())).toList());
        }
        return new SettingsView(text(dated.clerkLimitOn(today)), dated.floorOn(today).toPlainString(),
                dated.termsOn(today), dated.latePctOn(today).toPlainString(), service.policyDate(sheetId),
                service.roles(sheetId).stream().map(RoleView::of).toList(), service.periodNumberingFrom(sheetId),
                gradeTerms, text(dated.capOn(today)), history, text(dated.proofThresholdOn(today)),
                service.permissions(sheetId), service.graceDays(sheetId),
                service.delegations(sheetId).stream().map(DelegationView::of).toList(),
                text(dated.noteThresholdOn(today)), service.entryDate(sheetId),
                text(service.pot(sheetId)), service.parent(sheetId),
                service.fiscalStartMonth(sheetId), text(service.alertThreshold(sheetId)),
                service.escalateAfter(sheetId), service.reviewSla(sheetId));
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
