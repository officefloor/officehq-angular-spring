package net.officefloor.hq.app.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.sheet.DatedSetting;
import net.officefloor.hq.app.sheet.Sheet;
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
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class SettingsController {

    /** The sheet's settings as the screen shows them: the clerk limit with two decimals, null for
     *  none, the floor with two decimals, the terms in days, the late percent with two decimals,
     *  the policy date, null when blank, the period numbering from-date, null when blank (off),
     *  the terms each grade has of its own, a grade without them left out, the cap with two
     *  decimals, null for none, and the proof threshold with two decimals, null for none, each of the dated ones as in force on the sheet's today; and every
     *  value recorded for the dated settings, by key, each key's by from-date; and the permission
     *  matrix: the roles set for each action, comma separated, an action left blank left out. */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, String cap, String proofThreshold,
            Map<String, List<DatedValue>> dated, Map<String, String> permissions) {
        static SettingsView of(BigDecimal clerkLimit, BigDecimal floor, int terms, BigDecimal latePct, LocalDate policyDate,
                LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, BigDecimal cap, BigDecimal proofThreshold,
                Map<String, List<DatedSetting>> dated, Map<Permission, String> permissions) {
            Map<String, String> perms = new TreeMap<>();
            permissions.forEach((action, roles) -> perms.put(action.key(), roles));
            return new SettingsView(clerkLimit == null ? null : clerkLimit.toPlainString(), floor.toPlainString(), terms,
                    latePct.setScale(2).toPlainString(), policyDate, periodNumberingFrom, gradeTerms,
                    cap == null ? null : cap.setScale(2).toPlainString(),
                    proofThreshold == null ? null : proofThreshold.setScale(2).toPlainString(), DatedValue.of(dated), perms);
        }
    }

    /** A value recorded for a dated setting: in force from its from-date until the next one of its
     *  key; null for none. */
    public record DatedValue(LocalDate from, String value) {
        /** Every value recorded, by key, each key's by from-date. */
        public static Map<String, List<DatedValue>> of(Map<String, List<DatedSetting>> dated) {
            Map<String, List<DatedValue>> out = new TreeMap<>();
            dated.forEach((key, values) -> out.put(key,
                    values.stream().map(d -> new DatedValue(d.getFrom(), d.getValue())).toList()));
            return out;
        }
    }

    /** A from-date as a form submits it: a date as yyyy-mm-dd, blank or left out for the sheet's today. */
    private static final String FROM = "(\\d{4}-\\d{2}-\\d{2})?";

    /** The clerk limit as the form submits it: 0.00 or more with up to two decimals, blank for no
     *  limit, from a date (blank: the sheet's today). */
    public record ClerkLimitForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The floor as the form submits it: 0.00 or more with up to two decimals, blank for the
     *  default, from a date (blank: the sheet's today). */
    public record FloorForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The terms as the form submits them: a whole number of days, blank for the default, from a
     *  date (blank: the sheet's today). */
    public record TermsForm(@NotNull @Pattern(regexp = "(\\d{1,4})?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The late percent as the form submits it: 0.00 or more with up to two decimals, blank for
     *  the default, from a date (blank: the sheet's today). */
    public record LatePctForm(@NotNull @Pattern(regexp = "(\\d{1,3}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The cap as the form submits it: 0.00 or more with up to two decimals, blank for none, from
     *  a date (blank: the sheet's today). */
    public record CapForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The proof threshold as the form submits it: 0.00 or more with up to two decimals, blank
     *  for none, from a date (blank: the sheet's today). */
    public record ProofThresholdForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value,
            @Pattern(regexp = FROM) String from) {
    }

    /** The policy date as the form submits it: a date as yyyy-mm-dd, blank for none (the sheet's today). */
    public record PolicyDateForm(@NotNull @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String value) {
    }

    /** The period numbering from-date as the form submits it: a date as yyyy-mm-dd, blank for off. */
    public record PeriodNumberingFromForm(@NotNull @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String value) {
    }

    /** An action's roles as the form submits them: clerk and/or steward, comma separated, blank
     *  for the standing rule. */
    public record PermissionForm(@NotNull @Size(max = 100)
            @Pattern(regexp = "(\\s*(clerk|steward)\\s*(,\\s*(clerk|steward)\\s*)*)?") String value) {
    }

    /** A user name's role on the sheet. */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole r) {
            return new RoleView(r.getUser(), r.getRole().key());
        }
    }

    /** A role as the form submits it: a user name without spaces and clerk, steward or viewer. */
    public record RoleForm(
            @NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String user,
            @NotNull @Pattern(regexp = "clerk|steward|viewer") String role) {
    }

    /** The grades a person can hold. */
    private static final List<String> GRADES = List.of("A", "B", "C");

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public SettingsView settings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(sheetId);
    }

    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClerkLimitForm body) {
        BigDecimal limit = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setClerkLimit(sheetId, user.trim(), limit, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/floor")
    public SettingsView setFloor(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FloorForm body) {
        BigDecimal floor = body.value().isEmpty() ? Sheet.DEFAULT_FLOOR : new BigDecimal(body.value());
        service.setFloor(sheetId, user.trim(), floor, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/terms")
    public SettingsView setTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm body) {
        int terms = body.value().isEmpty() ? Sheet.DEFAULT_TERMS : Integer.parseInt(body.value());
        service.setTerms(sheetId, user.trim(), terms, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/terms/{grade}")
    public SettingsView setGradeTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable @NotNull @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm body) {
        Integer terms = body.value().isEmpty() ? null : Integer.valueOf(body.value());
        service.setGradeTerms(sheetId, user.trim(), grade, terms, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/late-pct")
    public SettingsView setLatePct(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody LatePctForm body) {
        BigDecimal latePct = body.value().isEmpty() ? Sheet.DEFAULT_LATE_PCT : new BigDecimal(body.value());
        service.setLatePct(sheetId, user.trim(), latePct, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/cap")
    public SettingsView setCap(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CapForm body) {
        BigDecimal cap = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setCap(sheetId, user.trim(), cap, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/proof-threshold")
    public SettingsView setProofThreshold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ProofThresholdForm body) {
        BigDecimal threshold = body.value().isEmpty() ? null : new BigDecimal(body.value());
        service.setProofThreshold(sheetId, user.trim(), threshold, fromOf(body.from()));
        return view(sheetId);
    }

    @PutMapping("/settings/policy-date")
    public SettingsView setPolicyDate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PolicyDateForm body) {
        LocalDate policyDate;
        try {
            policyDate = body.value().isEmpty() ? null : LocalDate.parse(body.value());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date");
        }
        service.setPolicyDate(sheetId, user.trim(), policyDate);
        return view(sheetId);
    }

    @PutMapping("/settings/period-numbering-from")
    public SettingsView setPeriodNumberingFrom(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PeriodNumberingFromForm body) {
        LocalDate from;
        try {
            from = body.value().isEmpty() ? null : LocalDate.parse(body.value());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date");
        }
        service.setPeriodNumberingFrom(sheetId, user.trim(), from);
        return view(sheetId);
    }

    @PutMapping("/settings/perm/{action}")
    public SettingsView setPermission(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable @NotNull @Pattern(regexp = "open|settle|void|reassign|close|reopen") String action,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PermissionForm body) {
        String roles = String.join(",", Arrays.stream(body.value().split(","))
                .map(String::trim).filter(r -> !r.isEmpty()).toList());
        service.setPermission(sheetId, user.trim(), Permission.of(action), roles);
        return view(sheetId);
    }

    private SettingsView view(String sheetId) {
        return SettingsView.of(service.clerkLimit(sheetId), service.floor(sheetId), service.terms(sheetId),
                service.latePct(sheetId), service.policyDate(sheetId), service.periodNumberingFrom(sheetId),
                gradeTerms(sheetId), service.cap(sheetId), service.proofThreshold(sheetId), service.datedSettings(sheetId),
                service.permissions(sheetId));
    }

    /** A from-date as the form submits it; null when blank or left out (the sheet's today). */
    private static LocalDate fromOf(String from) {
        if (from == null || from.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(from);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date");
        }
    }

    private Map<String, Integer> gradeTerms(String sheetId) {
        Map<String, Integer> out = new TreeMap<>();
        for (String grade : GRADES) {
            Integer terms = service.gradeTerms(sheetId, grade);
            if (terms != null) {
                out.put(grade, terms);
            }
        }
        return out;
    }

    @GetMapping("/roles")
    public List<RoleView> roles(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return service.roles(sheetId).stream().map(RoleView::of).toList();
    }

    @PutMapping("/roles")
    public RoleView setRole(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RoleForm body) {
        return RoleView.of(service.setRole(sheetId, user.trim(), body.user().trim(), Role.of(body.role())));
    }
}
