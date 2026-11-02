package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
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
@RequestMapping("/api/sheets/{sheetId}/settings")
@Validated
public class SettingsController {

    /** What the screen shows of a sheet's settings; the clerk limit as text with two decimals
     *  (null when there is no limit), the floor as text with two decimals, the terms in days, and the late percent as text with two
     *  decimals, and the policy date (null when it is blank: the sheet's today stands in), and the
     *  period numbering from date (null when it is blank: off), and the terms each grade has of its own
     *  (a grade not in it takes the sheet's terms). */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms) {
        static SettingsView of(Sheet sheet, Map<String, Integer> gradeTerms) {
            return of(sheet.getClerkLimit(), sheet.getFloor(), sheet.getTerms(), sheet.getLatePct(),
                    sheet.getPolicyDate(), sheet.getPeriodNumberingFrom(), gradeTerms);
        }

        static SettingsView of(BigDecimal clerkLimit, BigDecimal floor, int terms, BigDecimal latePct,
                LocalDate policyDate, LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms) {
            return new SettingsView(clerkLimit == null ? null : clerkLimit.setScale(2).toPlainString(),
                    floor.setScale(2).toPlainString(), terms, latePct.setScale(2).toPlainString(), policyDate,
                    periodNumberingFrom, gradeTerms);
        }
    }

    /** The clerk limit form: 0.00 or more with up to two decimals, or blank for no limit. */
    public record ClerkLimitForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value) {

        BigDecimal limitValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The floor form: 0.00 or more with up to two decimals. */
    public record FloorForm(@NotBlank @Pattern(regexp = "\\s*\\d{1,12}(\\.\\d{1,2})?\\s*") String value) {

        BigDecimal floorValue() {
            return new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The terms form: a whole number of days, 0 or more. */
    public record TermsForm(@NotBlank @Pattern(regexp = "\\s*\\d{1,4}\\s*") String value) {

        int termsValue() {
            return Integer.parseInt(value.trim());
        }
    }

    /** The late percent form: a percent of 0.00 or more with up to two decimals. */
    public record LatePctForm(@NotBlank @Pattern(regexp = "\\s*\\d{1,5}(\\.\\d{1,2})?\\s*") String value) {

        BigDecimal latePctValue() {
            return new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The policy date form: a date (yyyy-mm-dd), or blank for the sheet's today. */
    public record PolicyDateForm(@Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String value) {

        LocalDate policyDateValue() {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(value.trim());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + value.trim());
            }
        }
    }

    /** The period numbering from form: a date (yyyy-mm-dd), or blank for off. */
    public record PeriodNumberingFromForm(@Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String value) {

        LocalDate fromValue() {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(value.trim());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + value.trim());
            }
        }
    }

    private final SheetService service;

    public SettingsController(SheetService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String id = sheetId.trim();
        return SettingsView.of(service.clerkLimit(id), service.floor(id), service.terms(id), service.latePct(id),
                service.policyDate(id), service.periodNumberingFrom(id), service.gradeTerms(id));
    }

    /** The settings view of a sheet just saved. */
    private SettingsView view(Sheet sheet) {
        return SettingsView.of(sheet, service.gradeTerms(sheet.getId()));
    }

    @PutMapping("/clerk-limit")
    public SettingsView setClerkLimit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClerkLimitForm form) {
        return view(service.setClerkLimit(sheetId.trim(), user.trim(), form.limitValue()));
    }

    @PutMapping("/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FloorForm form) {
        return view(service.setFloor(sheetId.trim(), user.trim(), form.floorValue()));
    }

    @PutMapping("/terms")
    public SettingsView setTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm form) {
        return view(service.setTerms(sheetId.trim(), user.trim(), form.termsValue()));
    }

    @PutMapping("/terms/{grade}")
    public SettingsView setGradeTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm form) {
        return view(service.setGradeTerms(sheetId.trim(), user.trim(), grade, form.termsValue()));
    }

    @PutMapping("/late-pct")
    public SettingsView setLatePct(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody LatePctForm form) {
        return view(service.setLatePct(sheetId.trim(), user.trim(), form.latePctValue()));
    }

    @PutMapping("/policy-date")
    public SettingsView setPolicyDate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PolicyDateForm form) {
        return view(service.setPolicyDate(sheetId.trim(), user.trim(), form.policyDateValue()));
    }

    @PutMapping("/period-numbering-from")
    public SettingsView setPeriodNumberingFrom(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PeriodNumberingFromForm form) {
        return view(service.setPeriodNumberingFrom(sheetId.trim(), user.trim(), form.fromValue()));
    }
}
