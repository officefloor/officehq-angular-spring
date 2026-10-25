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
     *  when there is no limit; the floor has two decimals; the terms are the days after a claim's date
     *  it is due by; the late percent has two decimals; the policy date is null when blank (the sheet's
     *  today then); period numbering from is null when blank (sheet-wide numbering); grade terms are
     *  the terms each grade set for itself, by grade, a grade without its own using the sheet's; the cap
     *  has two decimals, or is null when there is no cap; so has the proof threshold, null when there is none. Each of these figures is the one in force on the
     *  sheet's today; dated holds, by key, every value the dated settings take from their from-dates. */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            List<RoleView> roles, LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, String cap,
            Map<String, List<DatedView>> dated, String proofThreshold) {
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

    /** The floor as entered. */
    public record FloorForm(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value,
            LocalDate from) {
    }

    /** The terms as entered: whole days. */
    public record TermsForm(@NotNull @Min(0) @Max(36500) Integer value, LocalDate from) {
    }

    /** A grade's own terms as entered: whole days; none means the sheet's terms. */
    public record GradeTermsForm(@Min(0) @Max(36500) Integer value, LocalDate from) {
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

    /** Set the sheet's terms: the days after a claim's date it is due by. */
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

    /** Assign a user name a role on the sheet. */
    @PutMapping("/roles")
    public RoleView setRole(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid RoleForm form) {
        Role role = Role.of(form.role().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such role."));
        return RoleView.of(service.setRole(sheetId.trim(), SignedInUser.name(user), form.user().trim(), role));
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
                gradeTerms, text(dated.capOn(today)), history, text(dated.proofThresholdOn(today)));
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
