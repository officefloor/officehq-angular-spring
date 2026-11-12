package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sheet endpoints. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    /** A sheet as the screen shows it: its today, its clerk limit (two decimals; none for no limit) and
     *  its floor (two decimals), its terms (days), its late percent (two decimals) and its policy date (none: the sheet's today) and its period numbering from-date (none: off), the terms of each grade that has its own, and its cap (two decimals; none for no cap), and its proof threshold (two decimals; none for no threshold), and its grace days (none: off), and whether it is archived.
     *  The dated settings show the value in force on the sheet's today; every value recorded for them is in
     *  settings. */
    public record SheetView(String id, LocalDate today, String clerkLimit, String floor, int terms, String latePct,
            LocalDate policyDate, LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, String cap,
            List<SettingView> settings, String proofThreshold, Map<String, String> permissions, Integer graceDays,
            boolean archived) {

        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(),
                    sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.getFloor().toPlainString(), sheet.getTerms(),
                    sheet.getLatePct().toPlainString(), sheet.getPolicyDate(),
                    sheet.getPeriodNumberingFrom(), sheet.getGradeTerms(),
                    sheet.getCap() == null ? null : sheet.getCap().toPlainString(),
                    sheet.getSettings().stream().map(SettingView::of).toList(),
                    sheet.getProofThreshold() == null ? null : sheet.getProofThreshold().toPlainString(),
                    sheet.getPermissions(), sheet.getGraceDays(), sheet.isArchived());
        }
    }

    /** A value recorded for a dated setting (key floor, late-pct, terms, terms-<grade>, clerk-limit, cap or proof-threshold)
     *  from a date; none for no limit, no cap, or a grade using the sheet's terms. */
    public record SettingView(String key, LocalDate from, String value) {

        static SettingView of(SheetSetting setting) {
            return new SettingView(setting.getKey(), setting.getFromDate(), setting.getValue());
        }
    }

    /** The sheet's clerk limit from a date; a missing limit means no limit, a missing date the sheet's today. */
    public record SettingsRequest(@DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal clerkLimit,
            LocalDate from) {
    }

    /** The sheet's floor from a date (missing: the sheet's today): the least a claim's live due can be. */
    public record FloorRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal floor,
            LocalDate from) {
    }

    /** The sheet's cap from a date (missing: the sheet's today): the most a person's due-total can be; a
     *  missing one means no cap. */
    public record CapRequest(@DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal cap,
            LocalDate from) {
    }

    /** The sheet's proof threshold from a date (missing: the sheet's today); a missing one means none. */
    public record ProofThresholdRequest(@DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal proofThreshold,
            LocalDate from) {
    }

    /** The sheet's terms from a date (missing: the sheet's today): the number of days after a claim's date it
     *  is due by. */
    public record TermsRequest(@NotNull @Min(0) @Max(36500) Integer terms, LocalDate from) {
    }

    /** A grade's own terms from a date (missing: the sheet's today); missing terms mean the grade uses the
     *  sheet's terms. */
    public record GradeTermsRequest(@Min(0) @Max(36500) Integer terms, LocalDate from) {
    }

    /** The sheet's late percent from a date (missing: the sheet's today): what an overdue claim's due gains of
     *  its figure for every full 30 days. */
    public record LatePctRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 7, fraction = 2) BigDecimal latePct,
            LocalDate from) {
    }

    /** The sheet's policy date; a missing one means the sheet's today. */
    public record PolicyDateRequest(LocalDate policyDate) {
    }

    /** The sheet's period numbering from-date; a missing one means off. */
    public record PeriodNumberingFromRequest(LocalDate periodNumberingFrom) {
    }

    /** The sheet's grace days; missing means off. */
    public record GraceDaysRequest(@Min(0) @Max(36500) Integer graceDays) {
    }

    /** The roles that may take an action, comma separated (clerk, steward); blank keeps the standing rule. */
    public record PermissionRequest(
            @Size(max = 200) @Pattern(regexp = "\\s*((clerk|steward)\\s*(,\\s*(clerk|steward)\\s*)*)?") String roles) {
    }

    public record TodayRequest(@NotNull LocalDate today) {
    }

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    /** Load a sheet by id, starting it empty if it was never loaded before. */
    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return SheetView.of(service.load(id.trim(), user.trim()));
    }

    /** Archive the sheet (a steward only). */
    @PostMapping("/{id}/archive")
    public SheetView archive(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return SheetView.of(service.archive(id.trim(), user.trim()));
    }

    /** Change the sheet's today. */
    @PutMapping("/{id}/today")
    public SheetView setToday(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid TodayRequest request) {
        return SheetView.of(service.setToday(id.trim(), user.trim(), request.today()));
    }

    /** Change the sheet's settings. */
    @PutMapping("/{id}/settings")
    public SheetView setSettings(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid SettingsRequest request) {
        return SheetView.of(service.setClerkLimit(id.trim(), user.trim(), request.clerkLimit(), request.from()));
    }

    /** Change the sheet's floor. */
    @PutMapping("/{id}/settings/floor")
    public SheetView setFloor(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid FloorRequest request) {
        return SheetView.of(service.setFloor(id.trim(), user.trim(), request.floor(), request.from()));
    }

    /** Change the sheet's cap. */
    @PutMapping("/{id}/settings/cap")
    public SheetView setCap(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid CapRequest request) {
        return SheetView.of(service.setCap(id.trim(), user.trim(), request.cap(), request.from()));
    }

    /** Change the sheet's proof threshold. */
    @PutMapping("/{id}/settings/proof-threshold")
    public SheetView setProofThreshold(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ProofThresholdRequest request) {
        return SheetView.of(service.setProofThreshold(id.trim(), user.trim(), request.proofThreshold(), request.from()));
    }

    /** Change the sheet's terms. */
    @PutMapping("/{id}/settings/terms")
    public SheetView setTerms(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid TermsRequest request) {
        return SheetView.of(service.setTerms(id.trim(), user.trim(), request.terms(), request.from()));
    }

    /** Change a grade's own terms. */
    @PutMapping("/{id}/settings/terms/{grade}")
    public SheetView setGradeTerms(@PathVariable @NotBlank @Size(max = 200) String id,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GradeTermsRequest request) {
        return SheetView.of(service.setGradeTerms(id.trim(), user.trim(), grade, request.terms(), request.from()));
    }

    /** Change the sheet's late percent. */
    @PutMapping("/{id}/settings/late-pct")
    public SheetView setLatePct(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid LatePctRequest request) {
        return SheetView.of(service.setLatePct(id.trim(), user.trim(), request.latePct(), request.from()));
    }

    /** Change the sheet's policy date. */
    @PutMapping("/{id}/settings/policy-date")
    public SheetView setPolicyDate(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PolicyDateRequest request) {
        return SheetView.of(service.setPolicyDate(id.trim(), user.trim(), request.policyDate()));
    }

    /** Change the roles that may take an action. */
    @PutMapping("/{id}/settings/permissions/{action}")
    public SheetView setPermission(@PathVariable @NotBlank @Size(max = 200) String id,
            @PathVariable @Pattern(regexp = "open|settle|void|reassign|close|reopen") String action,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PermissionRequest request) {
        return SheetView.of(service.setPermission(id.trim(), user.trim(), action, request.roles()));
    }

    /** Change the sheet's grace days. */
    @PutMapping("/{id}/settings/grace-days")
    public SheetView setGraceDays(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GraceDaysRequest request) {
        return SheetView.of(service.setGraceDays(id.trim(), user.trim(), request.graceDays()));
    }

    /** Change the sheet's period numbering from-date. */
    @PutMapping("/{id}/settings/period-numbering-from")
    public SheetView setPeriodNumberingFrom(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PeriodNumberingFromRequest request) {
        return SheetView.of(service.setPeriodNumberingFrom(id.trim(), user.trim(), request.periodNumberingFrom()));
    }
}
