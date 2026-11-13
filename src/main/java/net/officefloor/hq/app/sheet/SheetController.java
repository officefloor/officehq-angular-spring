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
import net.officefloor.hq.app.history.AsOf;
import org.springframework.format.annotation.DateTimeFormat;
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
     *  its floor (two decimals), its terms (days), its late percent (two decimals) and its policy date (none: the sheet's today) and its period numbering from-date (none: off), the terms of each grade that has its own, and its cap (two decimals; none for no cap), and its proof threshold (two decimals; none for no threshold), and its note threshold (two decimals; none for no threshold), and its review threshold (two decimals; none for no threshold), and its grace days (none: off), and whether it is archived, and its entry date (none: off), and the number of its last settle batch (none before the first), and its pot (two decimals; none for no pot), and its parent sheet's id (none for no parent), and its fiscal start month (1 to 12), and its alert threshold (two decimals; none for off), and its rounding mode (half-up or half-even; none: by the policy date), and its escalation gap (days; none: off), and its review SLA (days; none: none), and its proof kinds (comma separated; none: none), and its default grade (none: A).
     *  The dated settings show the value in force on the sheet's today; every value recorded for them is in
     *  settings. */
    public record SheetView(String id, LocalDate today, String clerkLimit, String floor, int terms, String latePct,
            LocalDate policyDate, LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, String cap,
            List<SettingView> settings, String proofThreshold, Map<String, String> permissions, Integer graceDays,
            boolean archived, String noteThreshold, LocalDate entryDate, Integer lastSettleBatch, String pot,
            String parent, int fiscalStartMonth, String alertThreshold, String rounding, String reviewThreshold, Integer escalateAfter,
            Integer reviewSla, String proofKinds, String defaultGrade) {

        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(),
                    sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.getFloor().toPlainString(), sheet.getTerms(),
                    sheet.getLatePct().toPlainString(), sheet.getPolicyDate(),
                    sheet.getPeriodNumberingFrom(), sheet.getGradeTerms(),
                    sheet.getCap() == null ? null : sheet.getCap().toPlainString(),
                    sheet.getSettings().stream().map(SettingView::of).toList(),
                    sheet.getProofThreshold() == null ? null : sheet.getProofThreshold().toPlainString(),
                    sheet.getPermissions(), sheet.getGraceDays(), sheet.isArchived(),
                    sheet.getNoteThreshold() == null ? null : sheet.getNoteThreshold().toPlainString(),
                    sheet.getEntryDate(), sheet.getLastSettleBatch() == 0 ? null : sheet.getLastSettleBatch(),
                    sheet.getPot() == null ? null : sheet.getPot().toPlainString(), sheet.getParent(),
                    sheet.getFiscalStartMonth(),
                    sheet.getAlertThreshold() == null ? null : sheet.getAlertThreshold().toPlainString(),
                    sheet.getRoundingMode(),
                    sheet.getReviewThreshold() == null ? null : sheet.getReviewThreshold().toPlainString(),
                    sheet.getEscalateAfter(), sheet.getReviewSla(), sheet.getProofKinds(),
                    sheet.getDefaultGrade());
        }
    }

    /** A value recorded for a dated setting (key floor, late-pct, terms, terms-<grade>, grace-<grade>, clerk-limit, cap, proof-threshold, proof-threshold-<grade>, note-threshold, review-threshold or rounding)
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

    /** The sheet's note threshold from a date (missing: the sheet's today); a missing one means none. */
    public record NoteThresholdRequest(@DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal noteThreshold,
            LocalDate from) {
    }

    /** The sheet's review threshold from a date (missing: the sheet's today); a missing one means none. */
    public record ReviewThresholdRequest(@DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal reviewThreshold,
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

    /** A grade's proof threshold from a date (missing: the sheet's today); a missing one means the grade uses
     *  the sheet's. */
    public record GradeProofThresholdRequest(
            @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal proofThreshold, LocalDate from) {
    }

    /** A grade's grace days from a date (missing: the sheet's today); missing days mean the grade has none. */
    public record GradeGraceRequest(@Min(0) @Max(36500) Integer graceDays, LocalDate from) {
    }

    /** The sheet's late percent from a date (missing: the sheet's today): what an overdue claim's due gains of
     *  its figure for every full 30 days. */
    public record LatePctRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 7, fraction = 2) BigDecimal latePct,
            LocalDate from) {
    }

    /** The sheet's rounding mode from a date (missing: the sheet's today): half-up or half-even; a missing or
     *  blank one means none (a claim rounds by the policy date). */
    public record RoundingRequest(@Pattern(regexp = "|half-up|half-even") String rounding, LocalDate from) {
    }

    /** The sheet's policy date; a missing one means the sheet's today. */
    public record PolicyDateRequest(LocalDate policyDate) {
    }

    /** The sheet's period numbering from-date; a missing one means off. */
    public record PeriodNumberingFromRequest(LocalDate periodNumberingFrom) {
    }

    /** The sheet's entry date; a missing one means off. */
    public record EntryDateRequest(LocalDate entryDate) {
    }

    /** The sheet's grace days; missing means off. */
    public record GraceDaysRequest(@Min(0) @Max(36500) Integer graceDays) {
    }

    /** The sheet's escalation gap in days; missing means off. */
    public record EscalateAfterRequest(@Min(0) @Max(36500) Integer escalateAfter) {
    }

    /** The sheet's review SLA in days; missing means none. */
    public record ReviewSlaRequest(@Min(0) @Max(36500) Integer reviewSla) {
    }

    /** The sheet's proof kinds, comma separated (receipt or photo); missing or blank means none. */
    public record ProofKindsRequest(
            @Size(max = 200) @Pattern(regexp = "\\s*((receipt|photo)(\\s*,\\s*(receipt|photo))*)?\\s*") String proofKinds) {
    }

    /** The sheet's default grade, one of the grades; missing or blank means A. */
    public record DefaultGradeRequest(@Pattern(regexp = "\\s*[ABC]?\\s*") String defaultGrade) {
    }

    /** The sheet's fiscal start month, 1 to 12. */
    public record FiscalStartMonthRequest(@NotNull @Min(1) @Max(12) Integer fiscalStartMonth) {
    }

    /** The sheet's pot; missing means no pot. */
    public record PotRequest(@DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal pot) {
    }

    /** The sheet's alert threshold; missing means off. */
    public record AlertThresholdRequest(
            @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal alertThreshold) {
    }

    /** The sheet's parent sheet id; missing or blank means no parent. */
    public record ParentRequest(@Size(max = 200) String parent) {
    }

    /** The roles that may take an action, comma separated (clerk, steward); blank keeps the standing rule. */
    public record PermissionRequest(
            @Size(max = 200) @Pattern(regexp = "\\s*((clerk|steward)\\s*(,\\s*(clerk|steward)\\s*)*)?") String roles) {
    }

    public record TodayRequest(@NotNull LocalDate today) {
    }

    /** The id of the sheet to clone this one to: one never used. */
    public record CloneRequest(@Size(max = 200) String to) {
    }

    private final SheetService service;
    private final SheetCloneService cloneService;
    private final AsOf asOf;

    public SheetController(SheetService service, SheetCloneService cloneService, AsOf asOf) {
        this.service = service;
        this.cloneService = cloneService;
        this.asOf = asOf;
    }

    /** Load a sheet by id, starting it empty if it was never loaded before; as it stood at the end of a date
     *  when the screen views it as of one. */
    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestHeader(value = AsOf.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        Sheet sheet = service.load(id.trim(), user.trim());
        return SheetView.of(asOf == null ? sheet : this.asOf.sheet(sheet.getId(), asOf));
    }

    /** Archive the sheet (a steward only). */
    @PostMapping("/{id}/archive")
    public SheetView archive(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return SheetView.of(service.archive(id.trim(), user.trim()));
    }

    /** Clone the sheet to another id never used (a steward only). */
    @PostMapping("/{id}/clone")
    public SheetView cloneSheet(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid CloneRequest request) {
        return SheetView.of(cloneService.cloneSheet(id.trim(), user.trim(), request.to()));
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

    /** Change the sheet's note threshold. */
    @PutMapping("/{id}/settings/note-threshold")
    public SheetView setNoteThreshold(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid NoteThresholdRequest request) {
        return SheetView.of(service.setNoteThreshold(id.trim(), user.trim(), request.noteThreshold(), request.from()));
    }

    /** Change the sheet's review threshold. */
    @PutMapping("/{id}/settings/review-threshold")
    public SheetView setReviewThreshold(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ReviewThresholdRequest request) {
        return SheetView.of(service.setReviewThreshold(id.trim(), user.trim(), request.reviewThreshold(), request.from()));
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

    /** Change a grade's proof threshold. */
    @PutMapping("/{id}/settings/proof-threshold/{grade}")
    public SheetView setGradeProofThreshold(@PathVariable @NotBlank @Size(max = 200) String id,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GradeProofThresholdRequest request) {
        return SheetView.of(service.setGradeProofThreshold(id.trim(), user.trim(), grade, request.proofThreshold(),
                request.from()));
    }

    /** Change a grade's grace days. */
    @PutMapping("/{id}/settings/grace/{grade}")
    public SheetView setGradeGrace(@PathVariable @NotBlank @Size(max = 200) String id,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GradeGraceRequest request) {
        return SheetView.of(service.setGradeGrace(id.trim(), user.trim(), grade, request.graceDays(), request.from()));
    }

    /** Change the sheet's late percent. */
    @PutMapping("/{id}/settings/late-pct")
    public SheetView setLatePct(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid LatePctRequest request) {
        return SheetView.of(service.setLatePct(id.trim(), user.trim(), request.latePct(), request.from()));
    }

    /** Change the sheet's rounding mode. */
    @PutMapping("/{id}/settings/rounding")
    public SheetView setRounding(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid RoundingRequest request) {
        String mode = request.rounding() == null || request.rounding().isEmpty() ? null : request.rounding();
        return SheetView.of(service.setRounding(id.trim(), user.trim(), mode, request.from()));
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

    /** Change the sheet's escalation gap. */
    @PutMapping("/{id}/settings/escalate-after")
    public SheetView setEscalateAfter(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid EscalateAfterRequest request) {
        return SheetView.of(service.setEscalateAfter(id.trim(), user.trim(), request.escalateAfter()));
    }

    /** Change the sheet's review SLA. */
    @PutMapping("/{id}/settings/review-sla")
    public SheetView setReviewSla(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ReviewSlaRequest request) {
        return SheetView.of(service.setReviewSla(id.trim(), user.trim(), request.reviewSla()));
    }

    /** Change the sheet's proof kinds. */
    @PutMapping("/{id}/settings/proof-kinds")
    public SheetView setProofKinds(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ProofKindsRequest request) {
        return SheetView.of(service.setProofKinds(id.trim(), user.trim(), request.proofKinds()));
    }

    /** Change the sheet's default grade. */
    @PutMapping("/{id}/settings/default-grade")
    public SheetView setDefaultGrade(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid DefaultGradeRequest request) {
        return SheetView.of(service.setDefaultGrade(id.trim(), user.trim(), request.defaultGrade()));
    }

    /** Change the sheet's fiscal start month. */
    @PutMapping("/{id}/settings/fiscal-start-month")
    public SheetView setFiscalStartMonth(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid FiscalStartMonthRequest request) {
        return SheetView.of(service.setFiscalStartMonth(id.trim(), user.trim(), request.fiscalStartMonth()));
    }

    /** Change the sheet's pot. */
    @PutMapping("/{id}/settings/pot")
    public SheetView setPot(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PotRequest request) {
        return SheetView.of(service.setPot(id.trim(), user.trim(), request.pot()));
    }

    /** Change the sheet's alert threshold. */
    @PutMapping("/{id}/settings/alert-threshold")
    public SheetView setAlertThreshold(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid AlertThresholdRequest request) {
        return SheetView.of(service.setAlertThreshold(id.trim(), user.trim(), request.alertThreshold()));
    }

    /** Change the sheet's parent. */
    @PutMapping("/{id}/settings/parent")
    public SheetView setParent(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ParentRequest request) {
        return SheetView.of(service.setParent(id.trim(), user.trim(), request.parent()));
    }

    /** Change the sheet's entry date. */
    @PutMapping("/{id}/settings/entry-date")
    public SheetView setEntryDate(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid EntryDateRequest request) {
        return SheetView.of(service.setEntryDate(id.trim(), user.trim(), request.entryDate()));
    }

    /** Change the sheet's period numbering from-date. */
    @PutMapping("/{id}/settings/period-numbering-from")
    public SheetView setPeriodNumberingFrom(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PeriodNumberingFromRequest request) {
        return SheetView.of(service.setPeriodNumberingFrom(id.trim(), user.trim(), request.periodNumberingFrom()));
    }
}
