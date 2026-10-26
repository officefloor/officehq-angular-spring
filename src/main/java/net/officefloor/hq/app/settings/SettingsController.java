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
import net.officefloor.hq.app.session.SessionController;
import net.officefloor.hq.app.sheet.DatedSetting;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
     * when blank (off); and every value recorded for the dated settings, from which the screen works out the one in
     * force on any date.
     */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            LocalDate periodNumberingFrom, String cap, List<DatedSettingView> dated) {
        static SettingsView of(Sheet sheet) {
            LocalDate today = sheet.getToday();
            return new SettingsView(sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.floorOn(today).toPlainString(), sheet.termsOn(today), sheet.latePctOn(today).toPlainString(),
                    sheet.getPolicyDate(), sheet.getPeriodNumberingFrom(),
                    sheet.getCap() == null ? null : sheet.getCap().toPlainString(),
                    sheet.getSettings().stream().map(DatedSettingView::of)
                            .sorted(Comparator.comparing(DatedSettingView::key).thenComparing(DatedSettingView::from))
                            .toList());
        }
    }

    /**
     * One value recorded for a dated setting (clerk-limit, floor, terms, terms-<grade>, late-pct or cap), in force
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

    /** The policy date as the form submits it: a date, or null for blank (the sheet's today). */
    public record PolicyDateForm(LocalDate value) {
    }

    /** The period numbering from as the form submits it: a date, or null for blank (off). */
    public record PeriodNumberingFromForm(LocalDate value) {
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

    @PutMapping("/settings/cap")
    public SettingsView setCap(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody CapForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setCap(user, sheetId, form.value(), form.from()));
    }

    /** The roles set on the sheet, by user name; a name not listed is a clerk. */
    @GetMapping("/roles")
    public List<RoleView> roles(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.roles(sheetId).stream().map(RoleView::of).toList();
    }

    @PutMapping("/roles/{name}")
    public RoleView setRole(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @Valid @RequestBody RoleForm form, @SessionAttribute(SessionController.USER) String user) {
        return RoleView.of(service.setRole(user, sheetId, name, form.role()));
    }
}
