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
     * comma separated, or blank for the standing rule. Grace days: a whole number of days, or blank for off. An entry date: yyyy-mm-dd, or blank for off. Each setting that carries a figure may name the date it
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
            @Pattern(regexp = "\\s*|" + TERMS) String graceDays, @Pattern(regexp = DATE) String entryDate) {
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
     */
    public record SettingsResponse(String clerkLimit, String floor, int terms, String latePct, String policyDate,
            String periodNumberingFrom, Map<String, Integer> gradeTerms, String cap, String proofThreshold,
            String noteThreshold,
            List<DatedValue> dated, Map<String, String> permissions, String graceDays, String entryDate) {
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
                sheet.getEntryDate() == null ? "" : sheet.getEntryDate().toString());
    }

    private static DatedValue dated(DatedSetting s) {
        String value = s.getValue() == null ? ""
                : s.getKey().startsWith(SettingService.TERMS) ? String.valueOf(s.getValue().intValue())
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
        Map<String, String> permissions = new LinkedHashMap<>();
        permission(permissions, "open", req.permOpen());
        permission(permissions, "settle", req.permSettle());
        permission(permissions, "void", req.permVoid());
        permission(permissions, "reassign", req.permReassign());
        permission(permissions, "close", req.permClose());
        permission(permissions, "reopen", req.permReopen());
        String grace = req.graceDays() == null ? "" : req.graceDays().trim();
        String entry = req.entryDate() == null ? "" : req.entryDate().trim();
        return response(service.set(req.sheet().trim(), req.user().trim(),
                req.clerkLimit() == null ? null : amount(req.clerkLimit(), req.clerkLimitFrom()),
                req.floor() == null ? null : amount(req.floor(), req.floorFrom()),
                req.terms() == null ? null : terms(req.terms(), req.termsFrom()),
                req.latePct() == null ? null : amount(req.latePct(), req.latePctFrom()), req.policyDate() != null,
                policy.isEmpty() ? null : date(policy), req.periodNumberingFrom() != null,
                numberingFrom.isEmpty() ? null : date(numberingFrom),
                req.cap() == null ? null : amount(req.cap(), req.capFrom()), gradeTerms,
                req.proofThreshold() == null ? null : amount(req.proofThreshold(), req.proofThresholdFrom()),
                req.noteThreshold() == null ? null : amount(req.noteThreshold(), req.noteThresholdFrom()), permissions,
                req.graceDays() != null, grace.isEmpty() ? null : Integer.valueOf(grace),
                req.entryDate() != null, entry.isEmpty() ? null : date(entry)));
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

    /** Terms from a from-date (blank or null: the sheet's today). */
    private static SettingService.Dated<Integer> terms(String value, String from) {
        return new SettingService.Dated<>(Integer.valueOf(value.trim()), from(from));
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
