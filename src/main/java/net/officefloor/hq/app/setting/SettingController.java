package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The sheet's settings. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/settings")
@Validated
public class SettingController {

    /**
     * The settings as shown, each dated one as in force on the sheet's today: the clerk limit with two decimals, null
     * when there is none; the floor with two decimals; the terms in whole days; each grade's own terms in whole days,
     * by grade (a grade without its own is left out); the late percent with two decimals; the policy date as
     * yyyy-mm-dd, null when blank (the sheet's today); the period numbering from as yyyy-mm-dd, null when blank (off);
     * the entry date as yyyy-mm-dd, null when blank (off); the cap with two decimals, null when there is none; the grace days in whole days, null when blank (off); the fiscal year start month (1 to 12); the proof threshold with two decimals, null when there is none; the note threshold with two decimals, null when there is none; the review threshold with two decimals, null when there is none; the pot with two decimals, null when there is none; the alert threshold with two decimals, null when off; the parent sheet's id, null when there is none; the proof kinds, comma separated, null when there are none; the escalation gap in whole days, null when blank (off); the default grade, null when blank (A);
     * the permission matrix, by action (open, settle, void, reassign, close, reopen), the roles entered for it comma
     * separated, null when blank (the standing rule).
     * Then every value recorded of each dated setting, by key (clerk-limit, clerk-limit-A, clerk-limit-B, clerk-limit-C, floor, terms, terms-A, terms-B, terms-C,
     * grace-A, grace-B, grace-C, late-pct, cap, proof-threshold, proof-threshold-A, proof-threshold-B, proof-threshold-C, note-threshold, review-threshold, rounding), from-date first to last.
     */
    public record SettingsView(String clerkLimit, String floor, int terms, Map<String, Integer> gradeTerms,
            String latePct, LocalDate policyDate, LocalDate periodNumberingFrom, LocalDate entryDate, String cap,
            Integer graceDays, int fiscalStartMonth, String proofThreshold, String noteThreshold, String reviewThreshold, String pot, String alertThreshold, String parent, String proofKinds, Integer escalateAfter, String defaultGrade, Integer reviewSla, Map<String, String> permissions, Map<String, List<DatedValue>> dated) {

        static SettingsView of(Sheet sheet, Map<String, Integer> gradeTerms) {
            LocalDate today = sheet.getToday();
            Map<String, List<DatedValue>> dated = new LinkedHashMap<>();
            sheet.datedSettings().forEach((key, values) -> dated.put(key,
                    values.stream().map(d -> new DatedValue(d.getFromDate(), d.getValue())).toList()));
            Map<String, String> permissions = new LinkedHashMap<>();
            Sheet.PERMISSION_ACTIONS.forEach(action -> permissions.put(action, sheet.permission(action)));
            return new SettingsView(shown(sheet.getClerkLimit()), shown(sheet.floorOn(today)), sheet.termsOn(today),
                    gradeTerms, shown(sheet.latePctOn(today)), sheet.getPolicyDate(), sheet.getPeriodNumberingFrom(),
                    sheet.getEntryDate(), shown(sheet.getCap()), sheet.getGraceDays(), sheet.getFiscalStartMonth(), shown(sheet.proofThresholdOn(today)),
                    shown(sheet.noteThresholdOn(today)), shown(sheet.reviewThresholdOn(today)), shown(sheet.getPot()), shown(sheet.getAlertThreshold()), sheet.getParent(), sheet.getProofKinds(), sheet.getEscalateAfter(), sheet.getDefaultGrade(), sheet.getReviewSla(), permissions,
                    dated);
        }

        private static String shown(BigDecimal value) {
            return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        }
    }

    /** A dated setting's value (null: none) from its from-date. */
    public record DatedValue(LocalDate from, String value) {
    }

    /**
     * A clerk limit of 0.00 or more with up to two decimals, blank or missing meaning no limit, from the date (missing:
     * the sheet's today).
     */
    public record SettingsRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String clerkLimit,
            LocalDate from) {

        BigDecimal clerkLimitValue() {
            return clerkLimit == null || clerkLimit.isBlank() ? null
                    : new BigDecimal(clerkLimit.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** A floor of 0.00 or more with up to two decimals, from the date (missing: the sheet's today). */
    public record FloorRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String floor,
            LocalDate from) {

        BigDecimal floorValue() {
            return new BigDecimal(floor.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** Terms of 0 or more whole days, from the date (missing: the sheet's today). */
    public record TermsRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,5}\\s*") String terms,
            LocalDate from) {

        int termsValue() {
            return Integer.parseInt(terms.trim());
        }
    }

    /** Grace days of 0 or more whole days, blank or missing meaning none, from the date (missing: the sheet's today). */
    public record GradeGraceRequest(@Pattern(regexp = "\\s*(\\d{1,5})?\\s*") String grace, LocalDate from) {

        Integer graceValue() {
            return grace == null || grace.isBlank() ? null : Integer.valueOf(grace.trim());
        }
    }

    /** A late percent of 0.00 or more with up to two decimals, from the date (missing: the sheet's today). */
    public record LatePctRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,3}(\\.\\d{1,2})?\\s*") String latePct,
            LocalDate from) {

        BigDecimal latePctValue() {
            return new BigDecimal(latePct.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /**
     * A cap of 0.00 or more with up to two decimals, blank or missing meaning no cap, from the date (missing: the
     * sheet's today).
     */
    public record CapRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String cap,
            LocalDate from) {

        BigDecimal capValue() {
            return cap == null || cap.isBlank() ? null
                    : new BigDecimal(cap.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /**
     * A proof threshold of 0.00 or more with up to two decimals, blank or missing meaning none, from the date
     * (missing: the sheet's today).
     */
    public record ProofThresholdRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String proofThreshold,
            LocalDate from) {

        BigDecimal proofThresholdValue() {
            return proofThreshold == null || proofThreshold.isBlank() ? null
                    : new BigDecimal(proofThreshold.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /**
     * A note threshold of 0.00 or more with up to two decimals, blank or missing meaning none, from the date (missing:
     * the sheet's today).
     */
    public record NoteThresholdRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String noteThreshold,
            LocalDate from) {

        BigDecimal noteThresholdValue() {
            return noteThreshold == null || noteThreshold.isBlank() ? null
                    : new BigDecimal(noteThreshold.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /**
     * A review threshold of 0.00 or more with up to two decimals, blank or missing meaning none, from the date
     * (missing: the sheet's today).
     */
    public record ReviewThresholdRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String reviewThreshold,
            LocalDate from) {

        BigDecimal reviewThresholdValue() {
            return reviewThreshold == null || reviewThreshold.isBlank() ? null
                    : new BigDecimal(reviewThreshold.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /**
     * A rounding mode, half-up or half-even, blank or missing meaning none, from the date (missing: the sheet's today).
     */
    public record RoundingRequest(@Pattern(regexp = "\\s*(half-up|half-even)?\\s*") String rounding, LocalDate from) {

        String roundingValue() {
            return rounding == null || rounding.isBlank() ? null : rounding.trim();
        }
    }

    /** The roles that may take an action, comma separated (clerk, steward); blank or missing keeps the standing rule. */
    public record PermissionRequest(
            @Pattern(regexp = "\\s*((clerk|steward)(\\s*,\\s*(clerk|steward))*)?\\s*") String roles) {

        String rolesValue() {
            return roles == null || roles.isBlank() ? null : roles.replaceAll("\\s", "");
        }
    }

    /** A policy date as yyyy-mm-dd; null (blank) means the sheet's today. */
    public record PolicyDateRequest(LocalDate policyDate) {
    }

    /** Grace days of 0 or more whole days; blank or missing means off. */
    public record GraceDaysRequest(@Pattern(regexp = "\\s*(\\d{1,5})?\\s*") String graceDays) {

        Integer graceDaysValue() {
            return graceDays == null || graceDays.isBlank() ? null : Integer.valueOf(graceDays.trim());
        }
    }

    /** A review SLA of 0 or more whole days; blank or missing means none. */
    public record ReviewSlaRequest(@Pattern(regexp = "\\s*(\\d{1,5})?\\s*") String reviewSla) {

        Integer reviewSlaValue() {
            return reviewSla == null || reviewSla.isBlank() ? null : Integer.valueOf(reviewSla.trim());
        }
    }

    /** An escalation gap of 0 or more whole days; blank or missing means off. */
    public record EscalateAfterRequest(@Pattern(regexp = "\\s*(\\d{1,5})?\\s*") String escalateAfter) {

        Integer escalateAfterValue() {
            return escalateAfter == null || escalateAfter.isBlank() ? null : Integer.valueOf(escalateAfter.trim());
        }
    }

    /** A fiscal year start month, 1 to 12. */
    public record FiscalStartMonthRequest(@NotNull @Pattern(regexp = "\\s*(0?[1-9]|1[0-2])\\s*") String fiscalStartMonth) {

        int fiscalStartMonthValue() {
            return Integer.parseInt(fiscalStartMonth.trim());
        }
    }

    /** A pot of 0.00 or more with up to two decimals; blank or missing means none. */
    public record PotRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String pot) {

        BigDecimal potValue() {
            return pot == null || pot.isBlank() ? null : new BigDecimal(pot.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** An alert threshold of 0.00 or more with up to two decimals; blank or missing means off. */
    public record AlertThresholdRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String alertThreshold) {

        BigDecimal alertThresholdValue() {
            return alertThreshold == null || alertThreshold.isBlank() ? null
                    : new BigDecimal(alertThreshold.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** The proof kinds, comma separated, each receipt or photo; blank or missing means none. */
    public record ProofKindsRequest(
            @Pattern(regexp = "\\s*((receipt|photo)(\\s*,\\s*(receipt|photo))*)?\\s*") String proofKinds) {

        String proofKindsValue() {
            return proofKinds == null || proofKinds.isBlank() ? null
                    : String.join(",", Arrays.stream(proofKinds.split(",")).map(String::trim).toList());
        }
    }

    /** A default grade, A, B or C; blank or missing means A. */
    public record DefaultGradeRequest(@Pattern(regexp = "\\s*[ABC]?\\s*") String defaultGrade) {

        String defaultGradeValue() {
            return defaultGrade == null || defaultGrade.isBlank() ? null : defaultGrade.trim();
        }
    }

    /** A parent sheet's id; blank or missing means none. */
    public record ParentRequest(@Size(max = 100) String parent) {

        String parentValue() {
            return parent == null || parent.isBlank() ? null : parent.trim();
        }
    }

    /** A period numbering from as yyyy-mm-dd; null (blank) means off. */
    public record PeriodNumberingFromRequest(LocalDate periodNumberingFrom) {
    }

    /** An entry date as yyyy-mm-dd; null (blank) means off. */
    public record EntryDateRequest(LocalDate entryDate) {
    }

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.get(sheetId.trim()));
    }

    @PutMapping
    public SettingsView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid SettingsRequest request) {
        return view(service.setClerkLimit(sheetId.trim(), user.trim(), request.clerkLimitValue(), request.from()));
    }

    @PutMapping("/clerk-limit/{grade}")
    public SettingsView setGradeClerkLimit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid SettingsRequest request) {
        return view(service.setGradeClerkLimit(sheetId.trim(), user.trim(), grade, request.clerkLimitValue(),
                request.from()));
    }

    @PutMapping("/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FloorRequest request) {
        return view(service.setFloor(sheetId.trim(), user.trim(), request.floorValue(), request.from()));
    }

    @PutMapping("/terms")
    public SettingsView setTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TermsRequest request) {
        return view(service.setTerms(sheetId.trim(), user.trim(), request.termsValue(), request.from()));
    }

    @PutMapping("/terms/{grade}")
    public SettingsView setGradeTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TermsRequest request) {
        return view(service.setGradeTerms(sheetId.trim(), user.trim(), grade, request.termsValue(), request.from()));
    }

    @PutMapping("/grace/{grade}")
    public SettingsView setGradeGrace(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GradeGraceRequest request) {
        return view(service.setGradeGrace(sheetId.trim(), user.trim(), grade, request.graceValue(), request.from()));
    }

    @PutMapping("/late-pct")
    public SettingsView setLatePct(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid LatePctRequest request) {
        return view(service.setLatePct(sheetId.trim(), user.trim(), request.latePctValue(), request.from()));
    }

    @PutMapping("/policy-date")
    public SettingsView setPolicyDate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody PolicyDateRequest request) {
        return view(service.setPolicyDate(sheetId.trim(), user.trim(), request.policyDate()));
    }

    @PutMapping("/period-numbering-from")
    public SettingsView setPeriodNumberingFrom(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody PeriodNumberingFromRequest request) {
        return view(service.setPeriodNumberingFrom(sheetId.trim(), user.trim(), request.periodNumberingFrom()));
    }

    @PutMapping("/entry-date")
    public SettingsView setEntryDate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody EntryDateRequest request) {
        return view(service.setEntryDate(sheetId.trim(), user.trim(), request.entryDate()));
    }

    @PutMapping("/grace-days")
    public SettingsView setGraceDays(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GraceDaysRequest request) {
        return view(service.setGraceDays(sheetId.trim(), user.trim(), request.graceDaysValue()));
    }

    @PutMapping("/review-sla")
    public SettingsView setReviewSla(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ReviewSlaRequest request) {
        return view(service.setReviewSla(sheetId.trim(), user.trim(), request.reviewSlaValue()));
    }

    @PutMapping("/escalate-after")
    public SettingsView setEscalateAfter(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid EscalateAfterRequest request) {
        return view(service.setEscalateAfter(sheetId.trim(), user.trim(), request.escalateAfterValue()));
    }

    @PutMapping("/fiscal-start-month")
    public SettingsView setFiscalStartMonth(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FiscalStartMonthRequest request) {
        return view(service.setFiscalStartMonth(sheetId.trim(), user.trim(), request.fiscalStartMonthValue()));
    }

    @PutMapping("/pot")
    public SettingsView setPot(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PotRequest request) {
        return view(service.setPot(sheetId.trim(), user.trim(), request.potValue()));
    }

    @PutMapping("/alert-threshold")
    public SettingsView setAlertThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid AlertThresholdRequest request) {
        return view(service.setAlertThreshold(sheetId.trim(), user.trim(), request.alertThresholdValue()));
    }

    @PutMapping("/proof-kinds")
    public SettingsView setProofKinds(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ProofKindsRequest request) {
        return view(service.setProofKinds(sheetId.trim(), user.trim(), request.proofKindsValue()));
    }

    @PutMapping("/default-grade")
    public SettingsView setDefaultGrade(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid DefaultGradeRequest request) {
        return view(service.setDefaultGrade(sheetId.trim(), user.trim(), request.defaultGradeValue()));
    }

    @PutMapping("/parent")
    public SettingsView setParent(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ParentRequest request) {
        return view(service.setParent(sheetId.trim(), user.trim(), request.parentValue()));
    }

    @PutMapping("/cap")
    public SettingsView setCap(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CapRequest request) {
        return view(service.setCap(sheetId.trim(), user.trim(), request.capValue(), request.from()));
    }

    @PutMapping("/proof-threshold")
    public SettingsView setProofThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ProofThresholdRequest request) {
        return view(service.setProofThreshold(sheetId.trim(), user.trim(), request.proofThresholdValue(),
                request.from()));
    }

    @PutMapping("/proof-threshold/{grade}")
    public SettingsView setGradeProofThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ProofThresholdRequest request) {
        return view(service.setGradeProofThreshold(sheetId.trim(), user.trim(), grade, request.proofThresholdValue(),
                request.from()));
    }

    @PutMapping("/note-threshold")
    public SettingsView setNoteThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid NoteThresholdRequest request) {
        return view(service.setNoteThreshold(sheetId.trim(), user.trim(), request.noteThresholdValue(),
                request.from()));
    }

    @PutMapping("/review-threshold")
    public SettingsView setReviewThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ReviewThresholdRequest request) {
        return view(service.setReviewThreshold(sheetId.trim(), user.trim(), request.reviewThresholdValue(),
                request.from()));
    }

    @PutMapping("/rounding")
    public SettingsView setRounding(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RoundingRequest request) {
        return view(service.setRounding(sheetId.trim(), user.trim(), request.roundingValue(), request.from()));
    }

    @PutMapping("/perm/{action}")
    public SettingsView setPermission(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "open|settle|void|reassign|close|reopen") String action,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PermissionRequest request) {
        return view(service.setPermission(sheetId.trim(), user.trim(), action, request.rolesValue()));
    }

    private SettingsView view(Sheet sheet) {
        return SettingsView.of(sheet, service.gradeTerms(sheet));
    }
}
