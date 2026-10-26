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
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
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
     * The sheet's settings as the screen shows them; the clerk limit carries two decimals, or is null for none, and
     * the floor carries two decimals, the terms are whole days, the late percent carries two decimals, and the
     * policy date is null when blank (the sheet's today), and the period numbering from is null when blank (off).
     */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            LocalDate periodNumberingFrom) {
        static SettingsView of(Sheet sheet) {
            return new SettingsView(sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.getFloor().toPlainString(), sheet.getTerms(), sheet.getLatePct().toPlainString(),
                    sheet.getPolicyDate(), sheet.getPeriodNumberingFrom());
        }
    }

    /** The clerk limit as the form submits it: 0.00 or more with up to two decimals, or null for no limit. */
    public record ClerkLimitForm(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value) {
    }

    /** The floor as the form submits it: 0.00 or more with up to two decimals. */
    public record FloorForm(@NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal value) {
    }

    /** The terms as the form submits them: a whole number of days, 0 or more. */
    public record TermsForm(@NotNull @Min(0) @Max(99999) Integer value) {
    }

    /** The late percent as the form submits it: 0.00 or more with up to two decimals. */
    public record LatePctForm(@NotNull @DecimalMin("0.00") @Digits(integer = 5, fraction = 2) BigDecimal value) {
    }

    /** The policy date as the form submits it: a date, or null for blank (the sheet's today). */
    public record PolicyDateForm(LocalDate value) {
    }

    /** The period numbering from as the form submits it: a date, or null for blank (off). */
    public record PeriodNumberingFromForm(LocalDate value) {
    }

    /** The terms set for one grade, in whole days. */
    public record GradeTermsView(String grade, int terms) {
        static GradeTermsView of(GradeTerms t) {
            return new GradeTermsView(t.getGrade(), t.getTerms());
        }
    }

    public record RoleView(String user, String role) {
        static RoleView of(SheetRole r) {
            return new RoleView(r.getUser(), r.getRole());
        }
    }

    public record RoleForm(@NotNull String role) {
    }

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
        return SettingsView.of(service.setClerkLimit(user, sheetId, form.value()));
    }

    @PutMapping("/settings/floor")
    public SettingsView setFloor(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody FloorForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setFloor(user, sheetId, form.value()));
    }

    @PutMapping("/settings/terms")
    public SettingsView setTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody TermsForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setTerms(user, sheetId, form.value()));
    }

    /** The terms set per grade, by grade; a grade not listed uses the sheet's terms. */
    @GetMapping("/settings/grade-terms")
    public List<GradeTermsView> gradeTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.gradeTerms(sheetId).stream().map(GradeTermsView::of).toList();
    }

    @PutMapping("/settings/grade-terms/{grade}")
    public GradeTermsView setGradeTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade, @Valid @RequestBody TermsForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return GradeTermsView.of(service.setGradeTerms(user, sheetId, grade, form.value()));
    }

    @PutMapping("/settings/late-pct")
    public SettingsView setLatePct(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody LatePctForm form, @SessionAttribute(SessionController.USER) String user) {
        return SettingsView.of(service.setLatePct(user, sheetId, form.value()));
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
