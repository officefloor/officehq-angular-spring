package net.officefloor.hq.app.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.session.SessionController;
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
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class SettingsController {

    /**
     * The sheet's settings, as text with two decimals: the clerk limit (null when there is none) and
     * the floor; the terms in days; the terms in days of each grade that has its own; the late percent, with two decimals; and the policy date
     * (yyyy-mm-dd, null when there is none: the sheet's today is used); and the period numbering from-date
     * (yyyy-mm-dd, null when off).
     */
    public record SettingsView(String clerkLimit, String floor, int terms, Map<Grade, Integer> gradeTerms,
            String latePct, String policyDate,
            String periodNumberingFrom) {
        static SettingsView of(Sheet sheet) {
            BigDecimal limit = sheet.getClerkLimit();
            return new SettingsView(limit == null ? null : limit.setScale(2).toPlainString(),
                    sheet.getFloor().setScale(2).toPlainString(), sheet.getTerms(), sheet.getGradeTerms(),
                    sheet.getLatePct().setScale(2).toPlainString(),
                    sheet.getPolicyDate() == null ? null : sheet.getPolicyDate().toString(),
                    sheet.getPeriodNumberingFrom() == null ? null : sheet.getPeriodNumberingFrom().toString());
        }
    }

    /**
     * The settings to save; a setting left out (null) is not changed. The clerk limit is 0.00 or more
     * with up to two decimals, blank for no limit; the floor is 0.00 or more with up to two decimals; the terms are a whole number of days, 0 or more; so are a grade's own terms, blank for the sheet's; the late percent is 0.00 or more with up to two decimals; the policy date is yyyy-mm-dd, blank for none; so is the period numbering from-date.
     */
    public record SettingsForm(
            @Pattern(regexp = "\\s*|\\d{1,13}(\\.\\d{1,2})?") String clerkLimit,
            @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?") String floor,
            @Pattern(regexp = "\\d{1,4}") String terms,
            Map<Grade, @NotNull @Pattern(regexp = "\\s*|\\d{1,4}") String> gradeTerms,
            @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String latePct,
            @Pattern(regexp = "\\s*|\\d{4}-\\d{2}-\\d{2}") String policyDate,
            @Pattern(regexp = "\\s*|\\d{4}-\\d{2}-\\d{2}") String periodNumberingFrom) {

        BigDecimal clerkLimitValue() {
            return clerkLimit == null || clerkLimit.isBlank() ? null : new BigDecimal(clerkLimit).setScale(2);
        }

        BigDecimal floorValue() {
            return floor == null ? null : new BigDecimal(floor).setScale(2);
        }

        Integer termsValue() {
            return terms == null ? null : Integer.valueOf(terms);
        }

        /** The grades given, each with its own terms (null: blank, it uses the sheet's terms). */
        Map<Grade, Integer> gradeTermsValue() {
            Map<Grade, Integer> values = new HashMap<>();
            if (gradeTerms != null) {
                gradeTerms.forEach((grade, days) ->
                        values.put(grade, days.isBlank() ? null : Integer.valueOf(days.trim())));
            }
            return values;
        }

        BigDecimal latePctValue() {
            return latePct == null ? null : new BigDecimal(latePct).setScale(2);
        }

        LocalDate policyDateValue() {
            return dateValue(policyDate);
        }

        LocalDate periodNumberingFromValue() {
            return dateValue(periodNumberingFrom);
        }

        private static LocalDate dateValue(String text) {
            if (text == null || text.isBlank()) return null;
            try {
                return LocalDate.parse(text.trim());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + text);
            }
        }
    }

    /** A user name's role, in lower case (clerk or steward). */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole role) {
            return new RoleView(role.getUserName(), role.getRole().name().toLowerCase());
        }
    }

    public record RoleForm(
            @NotBlank @Size(max = 100) String user,
            @NotNull @Pattern(regexp = "clerk|steward") String role) {

        Role roleValue() {
            return Role.valueOf(role.toUpperCase());
        }
    }

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public SettingsView settings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return SettingsView.of(service.settings(sheetId, user));
    }

    /** Save the sheet's settings given; one left out keeps its value. */
    @PutMapping("/settings")
    public SettingsView save(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody SettingsForm form) {
        return SettingsView.of(service.save(sheetId, user, form.clerkLimit() != null, form.clerkLimitValue(),
                form.floorValue(), form.termsValue(), form.gradeTermsValue(), form.latePctValue(), form.policyDate() != null,
                form.policyDateValue(), form.periodNumberingFrom() != null, form.periodNumberingFromValue()));
    }

    /** The roles the sheet gives user names, by name. */
    @GetMapping("/roles")
    public List<RoleView> roles(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return service.roles(sheetId).stream().map(RoleView::of).toList();
    }

    /** Give a user name a role (clerk or steward); any other role is refused (400). */
    @PutMapping("/roles")
    public RoleView setRole(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody RoleForm form) {
        return RoleView.of(service.setRole(sheetId, user, form.user().trim(), form.roleValue()));
    }
}
