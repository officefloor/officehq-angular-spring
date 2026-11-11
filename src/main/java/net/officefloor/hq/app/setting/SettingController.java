package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
     * The settings as shown: the clerk limit with two decimals, null when there is none; the floor with two decimals;
     * the terms in whole days; each grade's own terms in whole days, by grade (a grade without its own is left out);
     * the late percent with two decimals; the policy date as yyyy-mm-dd, null when blank (the
     * sheet's today); the period numbering from as yyyy-mm-dd, null when blank (off).
     */
    public record SettingsView(String clerkLimit, String floor, int terms, Map<String, Integer> gradeTerms,
            String latePct, LocalDate policyDate, LocalDate periodNumberingFrom) {

        static SettingsView of(Sheet sheet, Map<String, Integer> gradeTerms) {
            return new SettingsView(sheet.getClerkLimit() == null ? null
                    : sheet.getClerkLimit().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    sheet.getFloor().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), sheet.getTerms(),
                    gradeTerms, sheet.getLatePct().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), sheet.getPolicyDate(),
                    sheet.getPeriodNumberingFrom());
        }
    }

    /** A clerk limit of 0.00 or more with up to two decimals; blank or missing means no limit. */
    public record SettingsRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String clerkLimit) {

        BigDecimal clerkLimitValue() {
            return clerkLimit == null || clerkLimit.isBlank() ? null
                    : new BigDecimal(clerkLimit.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** A floor of 0.00 or more with up to two decimals. */
    public record FloorRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String floor) {

        BigDecimal floorValue() {
            return new BigDecimal(floor.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** Terms of 0 or more whole days. */
    public record TermsRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,5}\\s*") String terms) {

        int termsValue() {
            return Integer.parseInt(terms.trim());
        }
    }

    /** A late percent of 0.00 or more with up to two decimals. */
    public record LatePctRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,3}(\\.\\d{1,2})?\\s*") String latePct) {

        BigDecimal latePctValue() {
            return new BigDecimal(latePct.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** A policy date as yyyy-mm-dd; null (blank) means the sheet's today. */
    public record PolicyDateRequest(LocalDate policyDate) {
    }

    /** A period numbering from as yyyy-mm-dd; null (blank) means off. */
    public record PeriodNumberingFromRequest(LocalDate periodNumberingFrom) {
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
        return view(service.setClerkLimit(sheetId.trim(), user.trim(), request.clerkLimitValue()));
    }

    @PutMapping("/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FloorRequest request) {
        return view(service.setFloor(sheetId.trim(), user.trim(), request.floorValue()));
    }

    @PutMapping("/terms")
    public SettingsView setTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TermsRequest request) {
        return view(service.setTerms(sheetId.trim(), user.trim(), request.termsValue()));
    }

    @PutMapping("/terms/{grade}")
    public SettingsView setGradeTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TermsRequest request) {
        return view(service.setGradeTerms(sheetId.trim(), user.trim(), grade, request.termsValue()));
    }

    @PutMapping("/late-pct")
    public SettingsView setLatePct(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid LatePctRequest request) {
        return view(service.setLatePct(sheetId.trim(), user.trim(), request.latePctValue()));
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

    private SettingsView view(Sheet sheet) {
        return SettingsView.of(sheet, service.gradeTerms(sheet.getId()));
    }
}
