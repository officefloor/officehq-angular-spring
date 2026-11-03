package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.claim.ClaimController;
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

    public record GetSettingsRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user) {
    }

    /**
     * The settings to save; a setting left null is left as it is. A clerk limit: an amount with up
     * to two decimals, or blank for no limit. A floor: an amount with up to two decimals. Terms: a
     * whole number of days. A late percent: 0.00 to 999.99, with up to two decimals. A policy date:
     * yyyy-mm-dd, or blank for none (the sheet's today). A period-numbering-from date: yyyy-mm-dd,
     * or blank for off. A grade's own terms (A, B or C): a whole number of days.
     */
    public record SetSettingsRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @Pattern(regexp = "\\s*|" + ClaimController.AMOUNT) String clerkLimit,
            @Pattern(regexp = ClaimController.AMOUNT) String floor,
            @Pattern(regexp = TERMS) String terms,
            @Pattern(regexp = LATE_PCT) String latePct,
            @Pattern(regexp = DATE) String policyDate, @Pattern(regexp = DATE) String periodNumberingFrom,
            @Pattern(regexp = TERMS) String termsA, @Pattern(regexp = TERMS) String termsB,
            @Pattern(regexp = TERMS) String termsC) {
    }

    /**
     * The settings in force; the clerk limit blank for none, the policy date blank for none, the
     * period-numbering-from date blank for off; the terms set for each grade that has its own.
     */
    public record SettingsResponse(String clerkLimit, String floor, int terms, String latePct, String policyDate,
            String periodNumberingFrom, Map<String, Integer> gradeTerms) {
    }

    private static SettingsResponse response(Sheet sheet) {
        return new SettingsResponse(SettingService.text(sheet.getClerkLimit()), SettingService.text(sheet.getFloor()),
                sheet.getTerms(), SettingService.text(sheet.getLatePct()),
                sheet.getPolicyDate() == null ? "" : sheet.getPolicyDate().toString(),
                sheet.getPeriodNumberingFrom() == null ? "" : sheet.getPeriodNumberingFrom().toString(),
                sheet.getGradeTerms());
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
        String limit = req.clerkLimit() == null ? "" : req.clerkLimit().trim();
        String policy = req.policyDate() == null ? "" : req.policyDate().trim();
        String numberingFrom = req.periodNumberingFrom() == null ? "" : req.periodNumberingFrom().trim();
        Map<String, Integer> gradeTerms = new TreeMap<>();
        if (req.termsA() != null) gradeTerms.put("A", Integer.valueOf(req.termsA().trim()));
        if (req.termsB() != null) gradeTerms.put("B", Integer.valueOf(req.termsB().trim()));
        if (req.termsC() != null) gradeTerms.put("C", Integer.valueOf(req.termsC().trim()));
        return response(service.set(req.sheet().trim(), req.user().trim(), req.clerkLimit() != null,
                limit.isEmpty() ? null : ClaimController.amount(limit),
                req.floor() == null ? null : ClaimController.amount(req.floor()),
                req.terms() == null ? null : Integer.valueOf(req.terms().trim()),
                req.latePct() == null ? null : ClaimController.amount(req.latePct()), req.policyDate() != null,
                policy.isEmpty() ? null : date(policy), req.periodNumberingFrom() != null,
                numberingFrom.isEmpty() ? null : date(numberingFrom), gradeTerms));
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + text + ".");
        }
    }
}
