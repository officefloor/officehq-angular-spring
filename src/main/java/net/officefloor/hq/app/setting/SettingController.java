package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.claim.ClaimController;
import net.officefloor.hq.app.sheet.DatedSetting;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Reading and saving the sheet's settings. */
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    /** A late percent: 0.00 to 999.99, with up to two decimals. */
    private static final String LATE_PCT = "\\d{1,3}(\\.\\d{1,2})?";

    /** Terms: a whole number of days. */
    private static final String TERMS = "\\s*\\d{1,4}\\s*";

    /** A date setting: yyyy-mm-dd, or blank for none. */
    private static final String DATE = "\\s*|\\s*\\d{4}-\\d{2}-\\d{2}\\s*";

    /** A permission matrix entry: roles (clerk, steward) comma separated, or blank for the standing rule. */
    private static final String ROLES = "\\s*|\\s*(?i:clerk|steward)(\\s*,\\s*(?i:clerk|steward))*\\s*";

    /** A rounding mode: half-up or half-even, or blank for none. */
    private static final String ROUNDING = "\\s*|\\s*(half-up|half-even)\\s*";

    /** A fiscal start month: a whole month number, 1 to 12. */
    private static final String FISCAL_MONTH = "\\s*(0?[1-9]|1[0-2])\\s*";

    public record GetSettingsRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user) {
    }

    /**
     * The settings to save; a setting left null is left as it is. A clerk limit: an amount with up
     * to two decimals, or blank for no limit. A floor: an amount with up to two decimals. Terms: a
     * whole number of days. A late percent: 0.00 to 999.99, with up to two decimals. A policy date:
     * yyyy-mm-dd, or blank for none (the sheet's today). A period-numbering-from date: yyyy-mm-dd,
     * or blank for off. A grade's own terms (A, B or C): a whole number of days. A cap: an amount with
     * up to two decimals, or blank for none. A proof threshold: an amount with up to two decimals, or blank
     * for none. A note threshold: an amount with up to two decimals, or blank for none. A permission matrix entry for open, settle, void, reassign, close or reopen: roles
     * comma separated, or blank for the standing rule. Grace days: a whole number of days, or blank for off. A grade's grace days (A, B or C): a whole number of days, or blank for none. A grade's own proof threshold (A, B or C): an amount with up to two decimals, or blank for none (the sheet's). An entry date: yyyy-mm-dd, or blank for off. A pot: an amount with up to two decimals, or blank for none. A parent: a sheet id, or blank for none. A fiscal start month: 1 to 12. An alert threshold: an amount with up to two decimals, or blank for off. A rounding mode: half-up or half-even, or blank for none. Each setting that carries a figure may name the date it
     * is in force from (its {@code ...From}: yyyy-mm-dd, or blank or null for the sheet's today).
     */
    public record SetSettingsRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @Pattern(regexp = "\\s*|" + ClaimController.AMOUNT) String clerkLimit,
            @Pattern(regexp = ClaimController.AMOUNT) String floor,
            @Pattern(regexp = TERMS) String terms,
            @Pattern(regexp = LATE_PCT) String latePct,
            @Pattern(regexp = DATE) String policyDate, @Pattern(regexp = DATE) String periodNumberingFrom,
            @Pattern(regexp = TERMS) String termsA, @Pattern(regexp = TERMS) String termsB,
            @Pattern(regexp = TERMS) String termsC, @Pattern(regexp = "\\s*|" + ClaimController.AMOUNT) String cap,
            @Pattern(regexp = DATE) String clerkLimitFrom, @Pattern(regexp = DATE) String floorFrom,
            @Pattern(regexp = DATE) String termsFrom, @Pattern(regexp = DATE) String latePctFrom,
            @Pattern(regexp = DATE) String termsAFrom, @Pattern(regexp = DATE) String termsBFrom,
            @Pattern(regexp = DATE) String termsCFrom, @Pattern(regexp = DATE) String capFrom,
            @Pattern(regexp = "\\s*|" + ClaimController.AMOUNT) String proofThreshold,
            @Pattern(regexp = DATE) String proofThresholdFrom,
            @Pattern(regexp = "\\s*|" + ClaimController.AMOUNT) String noteThreshold,
            @Pattern(regexp = DATE) String noteThresholdFrom,
            @Pattern(regexp = ROLES) String permOpen, @Pattern(regexp = ROLES) String permSettle,
            @Pattern(regexp = ROLES) String permVoid, @Pattern(regexp = ROLES) String permReassign,
            @Pattern(regexp = ROLES) String permClose, @Pattern(regexp = ROLES) String permReopen,
            @Pattern(regexp = "\\s*|" + TERMS) String graceDays, @Pattern(regexp = DATE) String entryDate,
            @Pattern(regexp = "\\s*|" + TERMS) String graceA, @Pattern(regexp = "\\s*|" + TERMS) String graceB,
            @Pattern(regexp = "\\s*|" + TERMS) String graceC, @Pattern(regexp = DATE) String graceAFrom,
            @Pattern(regexp = DATE) String graceBFrom, @Pattern(regexp = DATE) String graceCFrom,
            @Pattern(regexp = "\\s*|" + ClaimController.AMOUNT) String proofThresholdA,
            @Pattern(regexp = "\\s*|" + ClaimController.AMOUNT) String proofThresholdB,
            @Pattern(regexp = "\\s*|" + ClaimController.AMOUNT) String proofThresholdC,
            @Pattern(regexp = DATE) String proofThresholdAFrom, @Pattern(regexp = DATE) String proofThresholdBFrom,
            @Pattern(regexp = DATE) String proofThresholdCFrom,
            @Pattern(regexp = "\\s*|" + ClaimController.AMOUNT) String pot,
            @Size(max = 100) String parent,
            @Pattern(regexp = FISCAL_MONTH) String fiscalStartMonth,
            @Pattern(regexp = "\\s*|" + ClaimController.AMOUNT) String alertThreshold,
            @Pattern(regexp = ROUNDING) String rounding, @Pattern(regexp = DATE) String roundingFrom) {
    }

    /** A value recorded for a setting that carries a figure: blank from for the beginning of time, blank value for none. */
    public record DatedValue(String key, String from, String value) {
    }

    /**
     * The settings in force on the sheet's today; the clerk limit blank for none, the policy date
     * blank for none, the period-numbering-from date blank for off, the cap blank for none, the proof threshold blank for none, the note threshold blank for none; the terms
     * in force for each grade that has its own. And every value recorded for the settings that carry
     * a figure, by key and from-date. And the permission matrix: for each action, its roles as
     * entered, blank for the standing rule. And the grace days, blank for off. And the entry date, blank for off.
     * And the pot, blank for none. And the parent sheet's id, blank for none. And the fiscal start month.
     * And the alert threshold, blank for off. And the rounding mode, blank for none.
     */
    public record SettingsResponse(String clerkLimit, String floor, int terms, String latePct, String policyDate,
            String periodNumberingFrom, Map<String, Integer> gradeTerms, String cap, String proofThreshold,
            String noteThreshold,
            List<DatedValue> dated, Map<String, String> permissions, String graceDays, String entryDate, String pot,
            String parent, int fiscalStartMonth, String alertThreshold, String rounding) {
    }

    private static SettingsResponse response(Sheet sheet) {
        return new SettingsResponse(SettingService.text(sheet.getClerkLimit()), SettingService.text(sheet.getFloor()),
                sheet.getTerms(), SettingService.text(sheet.getLatePct()),
                sheet.getPolicyDate() == null ? "" : sheet.getPolicyDate().toString(),
                sheet.getPeriodNumberingFrom() == null ? "" : sheet.getPeriodNumberingFrom().toString(),
                sheet.getGradeTerms(), SettingService.text(sheet.getCap()), SettingService.text(sheet.getProofThreshold()),
                SettingService.text(sheet.getNoteThreshold()),
                sheet.getSettings().stream().map(SettingController::dated).toList(), sheet.getPermissions(),
                sheet.getGraceDays() == null ? "" : String.valueOf(sheet.getGraceDays()),
                sheet.getEntryDate() == null ? "" : sheet.getEntryDate().toString(), SettingService.text(sheet.getPot()),
                sheet.getParentId() == null ? "" : sheet.getParentId(), sheet.getFiscalStartMonth(),
                SettingService.text(sheet.getAlertThreshold()),
                sheet.getRounding() == null ? "" : sheet.getRounding());
    }

    private static DatedValue dated(DatedSetting s) {
        String value = s.getValue() == null ? ""
                : s.getKey().equals(SettingService.ROUNDING) ? Sheet.roundingName(s.getValue())
                : s.getKey().startsWith(SettingService.TERMS) || s.getKey().startsWith(SettingService.GRADE_GRACE) ? String.valueOf(s.getValue().intValue())
                : SettingService.text(s.getValue());
        return new DatedValue(s.getKey(), s.getFromDate() == null ? "" : s.getFromDate().toString(), value);
    }

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @PostMapping("/get")
    public SettingsResponse get(@Valid @RequestBody GetSettingsRequest req) {
        return response(service.get(req.sheet().trim(), req.user().trim()));
    }

    @PostMapping("/set")
    public SettingsResponse set(@Valid @RequestBody SetSettingsRequest req) {
        String policy = req.policyDate() == null ? "" : req.policyDate().trim();
        String numberingFrom = req.periodNumberingFrom() == null ? "" : req.periodNumberingFrom().trim();
        Map<String, SettingService.Dated<Integer>> gradeTerms = new TreeMap<>();
        if (req.termsA() != null) gradeTerms.put("A", terms(req.termsA(), req.termsAFrom()));
        if (req.termsB() != null) gradeTerms.put("B", terms(req.termsB(), req.termsBFrom()));
        if (req.termsC() != null) gradeTerms.put("C", terms(req.termsC(), req.termsCFrom()));
        Map<String, SettingService.Dated<Integer>> gradeGraces = new TreeMap<>();
        if (req.graceA() != null) gradeGraces.put("A", grace(req.graceA(), req.graceAFrom()));
        if (req.graceB() != null) gradeGraces.put("B", grace(req.graceB(), req.graceBFrom()));
        if (req.graceC() != null) gradeGraces.put("C", grace(req.graceC(), req.graceCFrom()));
        Map<String, SettingService.Dated<BigDecimal>> gradeProofThresholds = new TreeMap<>();
        if (req.proofThresholdA() != null) gradeProofThresholds.put("A", amount(req.proofThresholdA(), req.proofThresholdAFrom()));
        if (req.proofThresholdB() != null) gradeProofThresholds.put("B", amount(req.proofThresholdB(), req.proofThresholdBFrom()));
        if (req.proofThresholdC() != null) gradeProofThresholds.put("C", amount(req.proofThresholdC(), req.proofThresholdCFrom()));
        Map<String, String> permissions = new LinkedHashMap<>();
        permission(permissions, "open", req.permOpen());
        permission(permissions, "settle", req.permSettle());
        permission(permissions, "void", req.permVoid());
        permission(permissions, "reassign", req.permReassign());
        permission(permissions, "close", req.permClose());
        permission(permissions, "reopen", req.permReopen());
        String grace = req.graceDays() == null ? "" : req.graceDays().trim();
        String entry = req.entryDate() == null ? "" : req.entryDate().trim();
        String pot = req.pot() == null ? "" : req.pot().trim();
        String parent = req.parent() == null ? "" : req.parent().trim();
        String alert = req.alertThreshold() == null ? "" : req.alertThreshold().trim();
        return response(service.set(req.sheet().trim(), req.user().trim(),
                req.clerkLimit() == null ? null : amount(req.clerkLimit(), req.clerkLimitFrom()),
                req.floor() == null ? null : amount(req.floor(), req.floorFrom()),
                req.terms() == null ? null : terms(req.terms(), req.termsFrom()),
                req.latePct() == null ? null : amount(req.latePct(), req.latePctFrom()), req.policyDate() != null,
                policy.isEmpty() ? null : date(policy), req.periodNumberingFrom() != null,
                numberingFrom.isEmpty() ? null : date(numberingFrom),
                req.cap() == null ? null : amount(req.cap(), req.capFrom()), gradeTerms, gradeGraces,
                req.proofThreshold() == null ? null : amount(req.proofThreshold(), req.proofThresholdFrom()), gradeProofThresholds,
                req.noteThreshold() == null ? null : amount(req.noteThreshold(), req.noteThresholdFrom()), permissions,
                req.graceDays() != null, grace.isEmpty() ? null : Integer.valueOf(grace),
                req.entryDate() != null, entry.isEmpty() ? null : date(entry),
                req.pot() != null, pot.isEmpty() ? null : ClaimController.amount(pot),
                req.parent() != null, parent.isEmpty() ? null : parent,
                req.fiscalStartMonth() == null ? null : Integer.valueOf(req.fiscalStartMonth().trim()),
                req.alertThreshold() != null, alert.isEmpty() ? null : ClaimController.amount(alert),
                req.rounding() == null ? null : rounding(req.rounding(), req.roundingFrom())));
    }

    /** A permission matrix entry given: its roles, lower case, comma separated without spaces. */
    private static void permission(Map<String, String> permissions, String action, String roles) {
        if (roles != null) {
            permissions.put(action, roles.trim().isEmpty() ? ""
                    : String.join(",", Arrays.stream(roles.trim().split("\\s*,\\s*")).map(r -> r.toLowerCase(Locale.ROOT)).toList()));
        }
    }

    /** An amount (blank: none) from a from-date (blank or null: the sheet's today). */
    private static SettingService.Dated<BigDecimal> amount(String value, String from) {
        String text = value.trim();
        return new SettingService.Dated<>(text.isEmpty() ? null : ClaimController.amount(text), from(from));
    }

    /** A rounding mode (blank: none) from a from-date (blank or null: the sheet's today). */
    private static SettingService.Dated<String> rounding(String value, String from) {
        String text = value.trim();
        return new SettingService.Dated<>(text.isEmpty() ? null : text, from(from));
    }

    /** Terms from a from-date (blank or null: the sheet's today). */
    private static SettingService.Dated<Integer> terms(String value, String from) {
        return new SettingService.Dated<>(Integer.valueOf(value.trim()), from(from));
    }

    /** Grace days (blank: none) from a from-date (blank or null: the sheet's today). */
    private static SettingService.Dated<Integer> grace(String value, String from) {
        String text = value.trim();
        return new SettingService.Dated<>(text.isEmpty() ? null : Integer.valueOf(text), from(from));
    }

    private static LocalDate from(String text) {
        return text == null || text.isBlank() ? null : date(text.trim());
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + text + ".");
        }
    }
}
