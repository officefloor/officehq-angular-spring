package net.officefloor.hq.app.settings;

import jakarta.validation.Valid;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.session.SessionController;
import net.officefloor.hq.app.sheet.DatedSetting;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetController.SheetView;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class SettingsController {

    /**
     * The sheet's settings as the screen shows them: the clerk limit, the floor, the terms, the late percent and the
     * cap in force on the sheet's today (the clerk limit carries two decimals, or is null for none, the floor carries
     * two decimals, the terms are whole days, the late percent carries two decimals, and the cap carries two decimals,
     * or is null for none); the policy date, null when blank (the sheet's today), and the period numbering from, null
     * when blank (off); the entry date, null when blank (off); the grace days, null when blank (off); the pot, two decimals or null for none; the alert threshold, two decimals or null for off; the parent sheet's id, null for none; the fiscal start month, 1 to 12; the escalation gap in days, null when blank (off); the review SLA in days, null when blank (none); the proof kinds, comma separated, null when blank (none); and every value recorded for the dated settings, from which the screen works out the one in
     * force on any date; and the permission matrix as set, by action (an action not listed keeps the standing rule).
     */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            LocalDate periodNumberingFrom, LocalDate entryDate, Integer graceDays, String cap, List<DatedSettingView> dated, Map<String, String> permissions,
            String pot, String alertThreshold, String parent, int fiscalStartMonth,
            Integer escalateAfter, Integer reviewSla, String proofKinds) {
        static SettingsView of(Sheet sheet) {
            LocalDate today = sheet.getToday();
            return new SettingsView(sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.floorOn(today).toPlainString(), sheet.termsOn(today), sheet.latePctOn(today).toPlainString(),
                    sheet.getPolicyDate(), sheet.getPeriodNumberingFrom(), sheet.getEntryDate(), sheet.getGraceDays(),
                    sheet.getCap() == null ? null : sheet.getCap().toPlainString(),
                    sheet.getSettings().stream().map(DatedSettingView::of)
                            .sorted(Comparator.comparing(DatedSettingView::key).thenComparing(DatedSettingView::from))
                            .toList(),
                    sheet.getPermissions(), sheet.getPot() == null ? null : sheet.getPot().toPlainString(),
                    sheet.getAlertThreshold() == null ? null : sheet.getAlertThreshold().toPlainString(),
                    sheet.getParentId(), sheet.getFiscalStartMonth(), sheet.getEscalateAfter(),
                    sheet.getReviewSla(), sheet.getProofKinds());
        }
    }

    /**
     * One value recorded for a dated setting (clerk-limit, floor, terms, terms-<grade>, grace-<grade>, late-pct, cap,
     * proof-threshold, proof-threshold-<grade>, note-threshold, review-threshold or rounding), in force
     * from its from-date; null for none.
     */
    public record DatedSettingView(String key, LocalDate from, String value) {
        static DatedSettingView of(DatedSetting s) {
            return new DatedSettingView(s.getKey(), s.getFrom(), s.getValue());
        }
    }

    /**
     * The clerk limit as the form submits it: 0.00 or more with up to two decimals, or null for no limit; from the
     * date, or null for the sheet's today.
     */
    public record ClerkLimitForm(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /**
     * The cap as the form submits it: 0.00 or more with up to two decimals, or null for no cap; from the date, or
     * null for the sheet's today.
     */
    public record CapForm(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value, LocalDate from) {
    }

    /**
     * The proof threshold as the form submits it: 0.00 or more with up to two decimals, or null for none; from the
     * date, or null for the sheet's today.
     */
    public record ProofThresholdForm(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /**
     * The note threshold as the form submits it: 0.00 or more with up to two decimals, or null for none; from the
     * date, or null for the sheet's today.
     */
    public record NoteThresholdForm(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /**
     * The review threshold as the form submits it: 0.00 or more with up to two decimals, or null for none; from the
     * date, or null for the sheet's today.
     */
    public record ReviewThresholdForm(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /**
     * The rounding mode as the form submits it: half-up or half-even, or null (blank) for none; from the date, or null
     * for the sheet's today.
     */
    public record RoundingForm(@Size(max = 30) String value, LocalDate from) {
    }

    /** The floor as the form submits it: 0.00 or more with up to two decimals; from the date, or null for today. */
    public record FloorForm(@NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /** The terms as the form submits them: a whole number of days, 0 or more; from the date, or null for today. */
    public record TermsForm(@NotNull @Min(0) @Max(99999) Integer value, LocalDate from) {
    }

    /** The late percent as the form submits it: 0.00 or more with up to two decimals; from the date, or null for today. */
    public record LatePctForm(@NotNull @DecimalMin("0.00") @Digits(integer = 5, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /**
     * A permission as the form submits it: the roles that may take the action, comma separated, or null (blank) for
     * the standing rule.
     */
    public record PermissionForm(@Size(max = 100) String value) {
    }

    /** The policy date as the form submits it: a date, or null for blank (the sheet's today). */
    public record PolicyDateForm(LocalDate value) {
    }

    /** The period numbering from as the form submits it: a date, or null for blank (off). */
    public record PeriodNumberingFromForm(LocalDate value) {
    }

    /** The entry date as the form submits it: a date, or null for blank (off). */
    public record EntryDateForm(LocalDate value) {
    }

    /** The pot as the form submits it: 0.00 or more with up to two decimals, or null for blank (none). */
    public record PotForm(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value) {
    }

    /** The alert threshold as the form submits it: 0.00 or more with up to two decimals, or null for blank (off). */
    public record AlertThresholdForm(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value) {
    }

    /** The fiscal start month as the form submits it: 1 to 12. */
    public record FiscalStartMonthForm(@NotNull @Min(1) @Max(12) Integer value) {
    }

    /** The parent sheet as the form submits it: a sheet id, or null for blank (none). */
    public record ParentForm(@Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String value) {
    }

    /** The grace days as the form submits them: a whole number of days, 0 or more, or null for blank (off). */
    public record GraceDaysForm(@Min(0) @Max(99999) Integer value) {
    }

    /** The proof kinds as the form submits them: receipt or photo, comma separated, or null (blank) for none. */
    public record ProofKindsForm(@Size(max = 100) String value) {
    }

    /** The review SLA as the form submits it: a whole number of days, 0 or more, or null for blank (none). */
    public record ReviewSlaForm(@Min(0) @Max(99999) Integer value) {
    }

    /** The escalation gap as the form submits it: a whole number of days, 0 or more, or null for blank (off). */
    public record EscalateAfterForm(@Min(0) @Max(99999) Integer value) {
    }

    /**
     * A grade's grace days as the form submits them: a whole number of days, 0 or more, or null for blank (none); from
     * the date, or null for the sheet's today.
     */
    public record GradeGraceForm(@Min(0) @Max(99999) Integer value, LocalDate from) {
    }

    /** The terms in force on the sheet's today for one grade that has its own, in whole days. */
    public record GradeTermsView(String grade, int terms) {
    }

    public record RoleView(String user, String role) {
        static RoleView of(SheetRole r) {
            return new RoleView(r.getUser(), r.getRole());
        }
    }

    public record RoleForm(@NotNull String role) {
    }

    /** A delegation of the steward role: to the user, by the steward, from the from-date to the to-date, inclusive. */
    public record DelegationView(Long id, String user, String by, LocalDate from, LocalDate to) {
        static DelegationView of(Delegation d) {
            return new DelegationView(d.getId(), d.getUser(), d.getDelegatedBy(), d.getFrom(), d.getTo());
        }
    }

    /** A delegation as the form submits it: the user, and the from and to dates, each null for the sheet's today. */
    public record DelegationForm(@NotBlank @Size(max = 100) String user, LocalDate from, LocalDate to) {
    }

    private static final List<String> GRADES = List.of("A", "B", "C");

    private final SettingsService service;
    private final SheetService sheets;

    public SettingsController(SettingsService service, SheetService sheets) {
        this.service = service;
        this.sheets = sheets;
    }

    @GetMapping("/settings")
    public SettingsView settings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return SettingsView.of(sheets.load(sheetId));
    }

    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody ClerkLimitForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setClerkLimit(user, sheetId, form.value(), form.from()));
    }

    @PutMapping("/settings/floor")
    public SettingsView setFloor(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody FloorForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setFloor(user, sheetId, form.value(), form.from()));
    }

    @PutMapping("/settings/rounding")
    public SettingsView setRounding(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody RoundingForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setRounding(user, sheetId, form.value(), form.from()));
    }

    @PutMapping("/settings/terms")
    public SettingsView setTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody TermsForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setTerms(user, sheetId, form.value(), form.from()));
    }

    /**
     * The terms in force on the sheet's today per grade, by grade, for each grade that has its own then; a grade not
     * listed uses the sheet's terms.
     */
    @GetMapping("/settings/grade-terms")
    public List<GradeTermsView> gradeTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        Sheet sheet = sheets.load(sheetId);
        return GRADES.stream().flatMap(g -> sheet.gradeTermsOn(g, sheet.getToday())
                .map(t -> new GradeTermsView(g, t)).stream()).toList();
    }

    @PutMapping("/settings/grade-terms/{grade}")
    public SettingsView setGradeTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade, @Valid @RequestBody TermsForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setGradeTerms(user, sheetId, grade, form.value(), form.from()));
    }

    @PutMapping("/settings/grade-grace/{grade}")
    public SettingsView setGradeGrace(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade, @Valid @RequestBody GradeGraceForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setGradeGrace(user, sheetId, grade, form.value(), form.from()));
    }

    @PutMapping("/settings/grade-proof-threshold/{grade}")
    public SettingsView setGradeProofThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade, @Valid @RequestBody ProofThresholdForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setGradeProofThreshold(user, sheetId, grade, form.value(), form.from()));
    }

    @PutMapping("/settings/late-pct")
    public SettingsView setLatePct(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody LatePctForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setLatePct(user, sheetId, form.value(), form.from()));
    }

    @PutMapping("/settings/policy-date")
    public SettingsView setPolicyDate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody PolicyDateForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setPolicyDate(user, sheetId, form.value()));
    }

    @PutMapping("/settings/period-numbering-from")
    public SettingsView setPeriodNumberingFrom(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody PeriodNumberingFromForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setPeriodNumberingFrom(user, sheetId, form.value()));
    }

    @PutMapping("/settings/entry-date")
    public SettingsView setEntryDate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody EntryDateForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setEntryDate(user, sheetId, form.value()));
    }

    @PutMapping("/settings/grace-days")
    public SettingsView setGraceDays(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody GraceDaysForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setGraceDays(user, sheetId, form.value()));
    }

    @PutMapping("/settings/proof-kinds")
    public SettingsView setProofKinds(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody ProofKindsForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setProofKinds(user, sheetId, form.value()));
    }

    @PutMapping("/settings/review-sla")
    public SettingsView setReviewSla(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody ReviewSlaForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setReviewSla(user, sheetId, form.value()));
    }

    @PutMapping("/settings/escalate-after")
    public SettingsView setEscalateAfter(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody EscalateAfterForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setEscalateAfter(user, sheetId, form.value()));
    }

    @PutMapping("/settings/pot")
    public SettingsView setPot(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody PotForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setPot(user, sheetId, form.value()));
    }

    @PutMapping("/settings/alert-threshold")
    public SettingsView setAlertThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody AlertThresholdForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setAlertThreshold(user, sheetId, form.value()));
    }

    @PutMapping("/settings/fiscal-start-month")
    public SettingsView setFiscalStartMonth(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody FiscalStartMonthForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setFiscalStartMonth(user, sheetId, form.value()));
    }

    @PutMapping("/settings/parent")
    public SettingsView setParent(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody ParentForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setParent(user, sheetId, form.value()));
    }

    @PutMapping("/settings/cap")
    public SettingsView setCap(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody CapForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setCap(user, sheetId, form.value(), form.from()));
    }

    @PutMapping("/settings/proof-threshold")
    public SettingsView setProofThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody ProofThresholdForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setProofThreshold(user, sheetId, form.value(), form.from()));
    }

    @PutMapping("/settings/note-threshold")
    public SettingsView setNoteThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody NoteThresholdForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setNoteThreshold(user, sheetId, form.value(), form.from()));
    }

    @PutMapping("/settings/review-threshold")
    public SettingsView setReviewThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody ReviewThresholdForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setReviewThreshold(user, sheetId, form.value(), form.from()));
    }

    @PutMapping("/settings/permissions/{action}")
    public SettingsView setPermission(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable @NotBlank @Size(max = 20) String action, @Valid @RequestBody PermissionForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setPermission(user, sheetId, action, form.value()));
    }

    /** The roles set on the sheet, by user name; a name not listed is a clerk. */
    @GetMapping("/roles")
    public List<RoleView> roles(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.roles(sheetId).stream().map(RoleView::of).toList();
    }

    /** The role the signed-in user acts in on the sheet (a steward's delegation to them included). */
    @GetMapping("/my-role")
    public RoleView myRole(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @SessionAttribute(SessionController.USER) String user) {
        return new RoleView(user, service.roleOf(sheetId, user));
    }

    @PutMapping("/roles/{name}")
    public RoleView setRole(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @Valid @RequestBody RoleForm form, @SessionAttribute(SessionController.USER) String user) {
        return RoleView.of(service.setRole(user, sheetId, name, form.role()));
    }

    /** The delegations of the steward role made on the sheet, in the order they were made. */
    @GetMapping("/delegations")
    public List<DelegationView> delegations(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.delegations(sheetId).stream().map(DelegationView::of).toList();
    }

    @PostMapping("/delegations")
    public DelegationView delegate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody DelegationForm form, @SessionAttribute(SessionController.USER) String user) {
        return DelegationView.of(service.delegate(user, sheetId, form.user().trim(), form.from(), form.to()));
    }

    /** Archives the sheet; only a steward may. */
    @PostMapping("/archive")
    public SheetView archive(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @SessionAttribute(SessionController.USER) String user) {
        return SheetView.of(service.archive(user, sheetId));
    }
}
