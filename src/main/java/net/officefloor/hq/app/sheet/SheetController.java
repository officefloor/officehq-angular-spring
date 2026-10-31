package net.officefloor.hq.app.sheet;

import org.springframework.format.annotation.DateTimeFormat;
import net.officefloor.hq.app.history.SheetHistory;
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
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Sheet endpoints. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    /** A sheet as the screen shows it, each dated setting as it is in force on the sheet's today, with every dated value
     *  recorded; the clerk limit (null: no limit) and floor and late percent have exactly two decimals; terms is in days;
     *  the policy date is null when it is the sheet's today; the period numbering from is null when it is off; the cap (two decimals) is null when there is none, as are the proof threshold and the note threshold (two decimals); the permissions are the
     *  matrix entries set, by action (one missing keeps its standing rule); the grace days are null when off; archived is whether the sheet is archived;
     *  the entry date is null when it is off; gradeGrace is the grace days set per grade in force on the sheet's today;
     *  gradeProofThreshold is the proof threshold (two decimals) set per grade in force on the sheet's today;
     *  gradeClerkLimit is the clerk limit (two decimals) set per grade in force on the sheet's today;
     *  settleBatch is the number of the last settle batch, null when there has been none; the pot (two decimals) is null when there is none, as is the alert threshold (two decimals) when it is off;
     *  rounding is the rounding mode in force on the sheet's today (half-up or half-even), null when there is none;
     *  parent is the id of the sheet's parent sheet, null when it has none;
     *  reviewThreshold is the review threshold (two decimals) in force on the sheet's today, null when there is none;
     *  escalateAfter is the escalation gap in days, null when it is off;
     *  reviewSla is the review SLA in days, null when there is none;
     *  proofKinds is the sheet's proof kinds, comma separated, null when there are none;
     *  defaultGrade is the sheet's default grade, null when it sets none (then A);
     *  asOf is the date the sheet is shown as of, when it is (then the sheet is as it stood at the end of it, and today is that date), else null. */
    public record SheetView(String id, LocalDate today, String clerkLimit, String floor, int terms, String latePct,
            LocalDate policyDate, LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, String cap,
            List<DatedSettingView> settings, String proofThreshold, Map<String, String> permissions, Integer graceDays, boolean archived,
            LocalDate asOf, String noteThreshold, LocalDate entryDate, Map<String, Integer> gradeGrace,
            Map<String, String> gradeProofThreshold, Integer settleBatch, String pot, String parent, int fiscalStartMonth,
            String alertThreshold, String rounding, String reviewThreshold, Integer escalateAfter, Integer reviewSla,
            String proofKinds, String defaultGrade, Map<String, String> gradeClerkLimit) {

        static SheetView of(Sheet sheet) {
            return of(sheet, null);
        }

        static SheetView of(Sheet sheet, LocalDate asOf) {
            return new SheetView(sheet.getId(), sheet.getToday(),
                    sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.getFloor().toPlainString(), sheet.getTerms(), sheet.getLatePct().toPlainString(), sheet.getPolicyDate(),
                    sheet.getPeriodNumberingFrom(), sheet.getGradeTerms(),
                    sheet.getCap() == null ? null : sheet.getCap().toPlainString(),
                    sheet.getSettings().stream().map(s -> new DatedSettingView(s.getKey(), s.getFrom(), s.getValue())).toList(),
                    sheet.getProofThreshold() == null ? null : sheet.getProofThreshold().toPlainString(),
                    sheet.getPermissions(), sheet.getGraceDays(), sheet.isArchived(), asOf,
                    sheet.getNoteThreshold() == null ? null : sheet.getNoteThreshold().toPlainString(),
                    sheet.getEntryDate(), sheet.getGradeGrace(), sheet.getGradeProofThresholds(),
                    sheet.getLastSettleBatch() == 0 ? null : sheet.getLastSettleBatch(),
                    sheet.getPot() == null ? null : sheet.getPot().toPlainString(), sheet.getParentId(),
                    sheet.getFiscalStartMonth(),
                    sheet.getAlertThreshold() == null ? null : sheet.getAlertThreshold().toPlainString(),
                    sheet.getRounding(),
                    sheet.getReviewThreshold() == null ? null : sheet.getReviewThreshold().toPlainString(),
                    sheet.getEscalateAfter(), sheet.getReviewSla(), sheet.getProofKinds(),
                    sheet.getDefaultGrade(), sheet.getGradeClerkLimits());
        }
    }

    /** A dated setting's value from a from-date (null: from the beginning of time); a null value means none. */
    public record DatedSettingView(String key, LocalDate from, String value) {
    }

    public record TodayChange(@NotNull LocalDate today) {
    }

    /** The sheet's settings as the form submits them; a missing clerk limit means no limit, a missing from-date the sheet's today. */
    public record SettingsChange(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal clerkLimit, LocalDate from) {
    }

    /** The sheet's floor as the form submits it. */
    public record FloorChange(@NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal floor, LocalDate from) {
    }

    /** The sheet's terms, in days, as the form submits them. */
    public record TermsChange(@NotNull @Min(0) @Max(36500) Integer terms, LocalDate from) {
    }

    /** A grade's own terms, in days, as the form submits them; missing ones leave the grade on the sheet's terms. */
    public record GradeTermsChange(@Min(0) @Max(36500) Integer terms, LocalDate from) {
    }

    /** The sheet's late percent as the form submits it. */
    public record LatePctChange(@NotNull @DecimalMin("0.00") @Digits(integer = 3, fraction = 2) BigDecimal latePct, LocalDate from) {
    }

    /** The sheet's policy date as the form submits it; a missing one means the sheet's today. */
    public record PolicyDateChange(LocalDate policyDate) {
    }

    /** The sheet's entry date as the form submits it; a missing one means off. */
    public record EntryDateChange(LocalDate entryDate) {
    }

    /** The sheet's period numbering from as the form submits it; a missing one means off. */
    public record PeriodNumberingFromChange(LocalDate periodNumberingFrom) {
    }

    /** A grade's grace days as the form submits them; missing ones mean none. */
    public record GradeGraceChange(@Min(0) @Max(36500) Integer days, LocalDate from) {
    }

    /** The sheet's grace days as the form submits them; missing ones mean off. */
    public record GraceDaysChange(@Min(0) @Max(36500) Integer graceDays) {
    }

    /** The sheet's escalation gap, in days, as the form submits it; a missing one means off. */
    public record EscalateAfterChange(@Min(0) @Max(36500) Integer escalateAfter) {
    }

    /** The sheet's review SLA, in days, as the form submits it; a missing one means none. */
    public record ReviewSlaChange(@Min(0) @Max(36500) Integer reviewSla) {
    }

    /** The sheet's fiscal year start month as the form submits it, 1 to 12. */
    public record FiscalStartMonthChange(@NotNull @Min(1) @Max(12) Integer month) {
    }

    /** The sheet's pot as the form submits it; a missing one means none. */
    public record PotChange(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal pot) {
    }

    /** The sheet's alert threshold as the form submits it; a missing one means off. */
    public record AlertThresholdChange(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal alertThreshold) {
    }

    /** The sheet's parent as the form submits it: a sheet id; a missing (or blank) one means none. */
    public record ParentChange(@Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]*") String parent) {
    }

    /** The sheet's proof kinds as the form submits them: a comma separated list of receipt and photo, each at most
     *  once; a missing (or blank) one means none. */
    public record ProofKindsChange(@Size(max = 200) @Pattern(regexp = "|(receipt|photo)(,(receipt|photo))*") String proofKinds) {
    }

    /** The sheet's default grade as the form submits it: A, B or C; a missing (or blank) one means none (then A). */
    public record DefaultGradeChange(@Pattern(regexp = "|[ABC]") String defaultGrade) {
    }

    /** The sheet's cap as the form submits it; a missing one means none. */
    public record CapChange(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal cap, LocalDate from) {
    }

    /** The sheet's proof threshold as the form submits it; a missing one means none. */
    public record ProofThresholdChange(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal proofThreshold, LocalDate from) {
    }

    /** The sheet's note threshold as the form submits it; a missing one means none. */
    public record NoteThresholdChange(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal noteThreshold, LocalDate from) {
    }

    /** The sheet's review threshold as the form submits it; a missing one means none. */
    public record ReviewThresholdChange(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal reviewThreshold, LocalDate from) {
    }

    /** The sheet's rounding mode as the form submits it; a missing (or blank) one means none. */
    public record RoundingChange(@Size(max = 20) String rounding, LocalDate from) {
    }

    /** The roles that may take an action as the form submits them, comma separated; blank keeps the standing rule. */
    public record PermissionChange(@Size(max = 100) String roles) {
    }

    /** The id of the sheet to clone this one to, as the form submits it. */
    public record CloneRequest(@NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String to) {
    }

    private final SheetService sheets;
    private final RoleService roles;
    private final SheetHistory history;
    private final SheetCloneService cloner;

    public SheetController(SheetService sheets, RoleService roles, SheetHistory history, SheetCloneService cloner) {
        this.sheets = sheets;
        this.roles = roles;
        this.history = history;
        this.cloner = cloner;
    }

    /** Load (re-read) a sheet by id, starting it empty if it has never been loaded; as it stood at the end of the
     *  as-of date, when one is sent. */
    @PostMapping("/{id}/load")
    public SheetView load(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        Sheet live = sheets.load(id, user);
        return asOf == null ? SheetView.of(live) : SheetView.of(history.sheetAsOf(live, asOf), asOf);
    }

    /** Archive the sheet: a steward only. */
    @PostMapping("/{id}/archive")
    public SheetView archive(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        if (!Role.STEWARD.equals(roles.roleOf(id, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can archive the sheet");
        }
        return SheetView.of(sheets.archive(id, user));
    }

    /** Clone the sheet to another id, never used before: a steward only. Answers with the clone. */
    @PostMapping("/{id}/clone")
    public SheetView cloneSheet(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CloneRequest request) {
        return SheetView.of(cloner.cloneSheet(id, user, request.to()));
    }

    /** Change the sheet's today (an ISO date). */
    @PutMapping("/{id}/today")
    public SheetView setToday(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TodayChange request) {
        return SheetView.of(sheets.setToday(id, user, request.today()));
    }

    /** Change the sheet's settings. */
    @PutMapping("/{id}/settings")
    public SheetView setSettings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody SettingsChange request) {
        return SheetView.of(sheets.setClerkLimit(id, user, request.clerkLimit(), request.from()));
    }

    /** Change the sheet's floor. */
    @PutMapping("/{id}/settings/floor")
    public SheetView setFloor(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FloorChange request) {
        return SheetView.of(sheets.setFloor(id, user, request.floor(), request.from()));
    }

    /** Change the sheet's terms. */
    @PutMapping("/{id}/settings/terms")
    public SheetView setTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsChange request) {
        return SheetView.of(sheets.setTerms(id, user, request.terms(), request.from()));
    }

    /** Change a grade's own terms. */
    @PutMapping("/{id}/settings/terms/{grade}")
    public SheetView setGradeTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GradeTermsChange request) {
        return SheetView.of(sheets.setGradeTerms(id, user, grade, request.terms(), request.from()));
    }

    /** Change the sheet's late percent. */
    @PutMapping("/{id}/settings/late-pct")
    public SheetView setLatePct(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody LatePctChange request) {
        return SheetView.of(sheets.setLatePct(id, user, request.latePct(), request.from()));
    }

    /** Change the sheet's policy date. */
    @PutMapping("/{id}/settings/policy-date")
    public SheetView setPolicyDate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PolicyDateChange request) {
        return SheetView.of(sheets.setPolicyDate(id, user, request.policyDate()));
    }

    /** Change the sheet's entry date. */
    @PutMapping("/{id}/settings/entry-date")
    public SheetView setEntryDate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody EntryDateChange request) {
        return SheetView.of(sheets.setEntryDate(id, user, request.entryDate()));
    }

    /** Change the sheet's period numbering from. */
    @PutMapping("/{id}/settings/period-numbering-from")
    public SheetView setPeriodNumberingFrom(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PeriodNumberingFromChange request) {
        return SheetView.of(sheets.setPeriodNumberingFrom(id, user, request.periodNumberingFrom()));
    }

    /** Change a grade's grace days. */
    @PutMapping("/{id}/settings/grace/{grade}")
    public SheetView setGradeGrace(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GradeGraceChange request) {
        return SheetView.of(sheets.setGradeGrace(id, user, grade, request.days(), request.from()));
    }

    /** Change the sheet's grace days. */
    @PutMapping("/{id}/settings/grace-days")
    public SheetView setGraceDays(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GraceDaysChange request) {
        return SheetView.of(sheets.setGraceDays(id, user, request.graceDays()));
    }

    /** Change the sheet's escalation gap. */
    @PutMapping("/{id}/settings/escalate-after")
    public SheetView setEscalateAfter(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody EscalateAfterChange request) {
        return SheetView.of(sheets.setEscalateAfter(id, user, request.escalateAfter()));
    }

    /** Change the sheet's review SLA. */
    @PutMapping("/{id}/settings/review-sla")
    public SheetView setReviewSla(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ReviewSlaChange request) {
        return SheetView.of(sheets.setReviewSla(id, user, request.reviewSla()));
    }

    /** Change the sheet's fiscal year start month. */
    @PutMapping("/{id}/settings/fiscal-start-month")
    public SheetView setFiscalStartMonth(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FiscalStartMonthChange request) {
        return SheetView.of(sheets.setFiscalStartMonth(id, user, request.month()));
    }

    /** Change the sheet's pot. */
    @PutMapping("/{id}/settings/pot")
    public SheetView setPot(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PotChange request) {
        return SheetView.of(sheets.setPot(id, user, request.pot()));
    }

    /** Change the sheet's alert threshold. */
    @PutMapping("/{id}/settings/alert-threshold")
    public SheetView setAlertThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody AlertThresholdChange request) {
        return SheetView.of(sheets.setAlertThreshold(id, user, request.alertThreshold()));
    }

    /** Change the sheet's proof kinds. */
    @PutMapping("/{id}/settings/proof-kinds")
    public SheetView setProofKinds(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ProofKindsChange request) {
        String kinds = request.proofKinds() == null || request.proofKinds().isEmpty() ? null : request.proofKinds();
        return SheetView.of(sheets.setProofKinds(id, user, kinds));
    }

    /** Change the sheet's default grade. */
    @PutMapping("/{id}/settings/default-grade")
    public SheetView setDefaultGrade(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody DefaultGradeChange request) {
        String grade = request.defaultGrade() == null || request.defaultGrade().isEmpty() ? null : request.defaultGrade();
        return SheetView.of(sheets.setDefaultGrade(id, user, grade));
    }

    /** Change the sheet's parent sheet. */
    @PutMapping("/{id}/settings/parent")
    public SheetView setParent(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ParentChange request) {
        String parent = request.parent() == null || request.parent().isEmpty() ? null : request.parent();
        return SheetView.of(sheets.setParent(id, user, parent));
    }

    /** Change the sheet's cap. */
    @PutMapping("/{id}/settings/cap")
    public SheetView setCap(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CapChange request) {
        return SheetView.of(sheets.setCap(id, user, request.cap(), request.from()));
    }

    /** Change the sheet's proof threshold. */
    @PutMapping("/{id}/settings/proof-threshold")
    public SheetView setProofThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ProofThresholdChange request) {
        return SheetView.of(sheets.setProofThreshold(id, user, request.proofThreshold(), request.from()));
    }

    /** Change a grade's own clerk limit. */
    @PutMapping("/{id}/settings/clerk-limit/{grade}")
    public SheetView setGradeClerkLimit(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody SettingsChange request) {
        return SheetView.of(sheets.setGradeClerkLimit(id, user, grade, request.clerkLimit(), request.from()));
    }

    /** Change a grade's own proof threshold. */
    @PutMapping("/{id}/settings/proof-threshold/{grade}")
    public SheetView setGradeProofThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ProofThresholdChange request) {
        return SheetView.of(sheets.setGradeProofThreshold(id, user, grade, request.proofThreshold(), request.from()));
    }

    /** Change the sheet's note threshold. */
    @PutMapping("/{id}/settings/note-threshold")
    public SheetView setNoteThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody NoteThresholdChange request) {
        return SheetView.of(sheets.setNoteThreshold(id, user, request.noteThreshold(), request.from()));
    }

    /** Change the sheet's review threshold. */
    @PutMapping("/{id}/settings/review-threshold")
    public SheetView setReviewThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ReviewThresholdChange request) {
        return SheetView.of(sheets.setReviewThreshold(id, user, request.reviewThreshold(), request.from()));
    }

    /** Change the sheet's rounding mode. */
    @PutMapping("/{id}/settings/rounding")
    public SheetView setRounding(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RoundingChange request) {
        String mode = request.rounding() == null || request.rounding().isBlank() ? null : request.rounding().trim();
        return SheetView.of(sheets.setRounding(id, user, mode, request.from()));
    }

    /** Change the roles that may take an action. */
    @PutMapping("/{id}/settings/perm/{action}")
    public SheetView setPermission(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @PathVariable @Pattern(regexp = "open|settle|void|reassign|close|reopen") String action,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PermissionChange request) {
        return SheetView.of(sheets.setPermission(id, user, action, request.roles()));
    }
}
