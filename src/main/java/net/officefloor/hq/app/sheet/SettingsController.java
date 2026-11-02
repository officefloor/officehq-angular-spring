package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
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

    /** What the screen shows of a sheet's settings, each dated one as in force on the sheet's today; the clerk limit as text with two decimals
     *  (null when there is no limit), the floor as text with two decimals, the terms in days, and the late percent as text with two
     *  decimals, and the policy date (null when it is blank: the sheet's today stands in), and the
     *  period numbering from date (null when it is blank: off), and the terms each grade has of its own
     *  (a grade not in it takes the sheet's terms), and the cap as text with two decimals (null when
     *  there is no cap), and the floors, late percents and caps recorded, each from its from-date, earliest
     *  first (a claim takes the one in force on its date, or else the sheet's starting one); and the
     *  proof threshold as text with two decimals (null when there is none); and the permission matrix:
     *  for each action, the comma separated roles that may take it (blank: the standing rule); and the
     *  grace days (null when blank: off). */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, String cap, List<DatedValue> floors,
            List<DatedValue> latePcts, List<DatedValue> caps, String proofThreshold,
            Map<String, String> permissions, Integer graceDays) {
        static SettingsView of(BigDecimal clerkLimit, BigDecimal floor, int terms, BigDecimal latePct,
                LocalDate policyDate, LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms,
                BigDecimal cap, List<SettingValue> floors, List<SettingValue> latePcts,
                List<SettingValue> caps, BigDecimal proofThreshold, Map<String, String> permissions,
                Integer graceDays) {
            return new SettingsView(clerkLimit == null ? null : clerkLimit.setScale(2).toPlainString(),
                    floor.setScale(2).toPlainString(), terms, latePct.setScale(2).toPlainString(), policyDate,
                    periodNumberingFrom, gradeTerms, cap == null ? null : cap.setScale(2).toPlainString(),
                    floors.stream().map(DatedValue::of).toList(), latePcts.stream().map(DatedValue::of).toList(),
                    caps.stream().map(DatedValue::of).toList(),
                    proofThreshold == null ? null : proofThreshold.setScale(2).toPlainString(), permissions, graceDays);
        }
    }

    /** A value a dated setting takes from a date, as text with two decimals; null when blank. */
    public record DatedValue(LocalDate from, String value) {
        static DatedValue of(SettingValue v) {
            return new DatedValue(v.getFrom(), v.getValue() == null ? null : v.getValue().setScale(2).toPlainString());
        }
    }

    /** A yyyy-mm-dd date, or blank. */
    static final String DATE = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*";

    /** A dated setting's form: its from-date is a date, or blank for the sheet's today. */
    interface Dated {
        String from();

        default LocalDate fromValue() {
            return date(from());
        }
    }

    /** A date typed (yyyy-mm-dd), or null when blank. */
    static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + value.trim());
        }
    }

    /** The clerk limit form: 0.00 or more with up to two decimals, or blank for no limit; from a
     *  date, or blank for the sheet's today. */
    public record ClerkLimitForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal limitValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The cap form: 0.00 or more with up to two decimals, or blank for no cap; from a date, or
     *  blank for the sheet's today. */
    public record CapForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal capValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The proof threshold form: 0.00 or more with up to two decimals, or blank for none; from a
     *  date, or blank for the sheet's today. */
    public record ProofThresholdForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal thresholdValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The floor form: 0.00 or more with up to two decimals; from a date, or blank for the sheet's
     *  today. */
    public record FloorForm(@NotBlank @Pattern(regexp = "\\s*\\d{1,12}(\\.\\d{1,2})?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal floorValue() {
            return new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The terms form: a whole number of days, 0 or more; from a date, or blank for the sheet's
     *  today. */
    public record TermsForm(@NotBlank @Pattern(regexp = "\\s*\\d{1,4}\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        int termsValue() {
            return Integer.parseInt(value.trim());
        }
    }

    /** The late percent form: a percent of 0.00 or more with up to two decimals; from a date, or
     *  blank for the sheet's today. */
    public record LatePctForm(@NotBlank @Pattern(regexp = "\\s*\\d{1,5}(\\.\\d{1,2})?\\s*") String value,
            @Pattern(regexp = DATE) String from) implements Dated {

        BigDecimal latePctValue() {
            return new BigDecimal(value.trim()).setScale(2);
        }
    }

    /** The policy date form: a date (yyyy-mm-dd), or blank for the sheet's today. */
    public record PolicyDateForm(@Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String value) {

        LocalDate policyDateValue() {
            return date(value);
        }
    }

    /** The period numbering from form: a date (yyyy-mm-dd), or blank for off. */
    public record PeriodNumberingFromForm(@Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String value) {

        LocalDate fromValue() {
            return date(value);
        }
    }

    /** The grace days form: a whole number of days, 0 or more, or blank for off. */
    public record GraceDaysForm(@Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String value) {

        Integer graceDaysValue() {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        }
    }

    /** The permission form: a comma separated list of roles (clerk, steward), or blank for the
     *  standing rule. */
    public record PermissionForm(@Size(max = 200)
            @Pattern(regexp = "\\s*((clerk|steward)\\s*(,\\s*(clerk|steward)\\s*)*)?") String value) {

        /** The roles as stored: each once, comma separated with no spaces; "" when blank. */
        String rolesValue() {
            return value == null ? "" : String.join(",", Arrays.stream(value.split(","))
                    .map(String::trim).filter(r -> !r.isEmpty()).distinct().toList());
        }
    }

    private final SheetService service;

    public SettingsController(SheetService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return view(sheetId.trim());
    }

    /** The settings view of a sheet as the store holds it now. */
    private SettingsView view(String id) {
        return SettingsView.of(service.clerkLimit(id), service.floor(id), service.terms(id), service.latePct(id),
                service.policyDate(id), service.periodNumberingFrom(id), service.gradeTerms(id), service.cap(id),
                service.recorded(id, "floor"), service.recorded(id, "late-pct"),
                service.recorded(id, "cap"), service.proofThreshold(id), service.permissions(id),
                service.graceDays(id));
    }

    /** The settings view of a sheet just saved. */
    private SettingsView view(Sheet sheet) {
        return view(sheet.getId());
    }

    @PutMapping("/clerk-limit")
    public SettingsView setClerkLimit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClerkLimitForm form) {
        return view(service.setClerkLimit(sheetId.trim(), user.trim(), form.limitValue(), form.fromValue()));
    }

    @PutMapping("/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FloorForm form) {
        return view(service.setFloor(sheetId.trim(), user.trim(), form.floorValue(), form.fromValue()));
    }

    @PutMapping("/terms")
    public SettingsView setTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm form) {
        return view(service.setTerms(sheetId.trim(), user.trim(), form.termsValue(), form.fromValue()));
    }

    @PutMapping("/terms/{grade}")
    public SettingsView setGradeTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm form) {
        return view(service.setGradeTerms(sheetId.trim(), user.trim(), grade, form.termsValue(), form.fromValue()));
    }

    @PutMapping("/late-pct")
    public SettingsView setLatePct(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody LatePctForm form) {
        return view(service.setLatePct(sheetId.trim(), user.trim(), form.latePctValue(), form.fromValue()));
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

    @PutMapping("/cap")
    public SettingsView setCap(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CapForm form) {
        return view(service.setCap(sheetId.trim(), user.trim(), form.capValue(), form.fromValue()));
    }

    @PutMapping("/proof-threshold")
    public SettingsView setProofThreshold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ProofThresholdForm form) {
        return view(service.setProofThreshold(sheetId.trim(), user.trim(), form.thresholdValue(), form.fromValue()));
    }

    @PutMapping("/grace-days")
    public SettingsView setGraceDays(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GraceDaysForm form) {
        return view(service.setGraceDays(sheetId.trim(), user.trim(), form.graceDaysValue()));
    }

    @PutMapping("/permissions/{action}")
    public SettingsView setPermission(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "open|settle|void|reassign|close|reopen") String action,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PermissionForm form) {
        return view(service.setPermission(sheetId.trim(), user.trim(), action, form.rolesValue()));
    }
}
