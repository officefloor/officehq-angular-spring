package net.officefloor.hq.app.access;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's roles and settings. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class AccessController {

    public record RoleView(String user, String role) {

        static RoleView of(SheetRole r) {
            return new RoleView(r.getUserName(), r.getRole().key());
        }
    }

    /**
     * The sheet's settings as shown; a limit is a figure with two decimals, null for none; so is the floor;
     * the terms are a number of days, and so are the terms of grades A, B and C, null for a grade without
     * its own (it uses the sheet's); the late percent is a figure with two decimals; the policy date and
     * the period numbering from are dates (yyyy-mm-dd), null when blank.
     */
    public record SettingsView(String clerkLimit, String floor, String terms, String termsA, String termsB,
            String termsC, String latePct, String policyDate, String periodNumberingFrom) {

        static SettingsView of(BigDecimal limit, BigDecimal floor, int terms, Integer termsA, Integer termsB,
                Integer termsC, BigDecimal latePct, LocalDate policyDate, LocalDate periodNumberingFrom) {
            return new SettingsView(limit == null ? null : limit.toPlainString(), floor.toPlainString(),
                    Integer.toString(terms), termsA == null ? null : termsA.toString(),
                    termsB == null ? null : termsB.toString(), termsC == null ? null : termsC.toString(),
                    latePct.toPlainString(),
                    policyDate == null ? null : policyDate.toString(),
                    periodNumberingFrom == null ? null : periodNumberingFrom.toString());
        }
    }

    public record AccessView(List<RoleView> roles, SettingsView settings) {
    }

    /** A user name and a role: clerk or steward. */
    public record RoleForm(@NotBlank @Size(max = 200) String user, @NotNull @Pattern(regexp = "clerk|steward") String role) {
    }

    /**
     * The settings to save; one left out (null) is kept as it is. A clerk limit is 0.00 or more with up
     * to two decimals, blank meaning no limit; so is a floor, blank putting back 1.00. The terms are a
     * whole number of days, blank putting back 14; so are a grade's own terms, blank removing them (the
     * grade then uses the sheet's). The late percent is 0.00 or more with up to two
     * decimals, blank putting back 0.00. The policy date is a date (yyyy-mm-dd), blank meaning the
     * sheet's today. The period numbering from is a date (yyyy-mm-dd), blank meaning off.
     */
    public record SettingsForm(@Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String clerkLimit,
            @Pattern(regexp = "(\\d{1,12}(\\.\\d{1,2})?)?") String floor,
            @Pattern(regexp = "(\\d{1,5})?") String terms,
            @Pattern(regexp = "(\\d{1,5})?") String termsA,
            @Pattern(regexp = "(\\d{1,5})?") String termsB,
            @Pattern(regexp = "(\\d{1,5})?") String termsC,
            @Pattern(regexp = "(\\d{1,10}(\\.\\d{1,2})?)?") String latePct,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String policyDate,
            @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?") String periodNumberingFrom) {
    }

    private final AccessService service;

    public AccessController(AccessService service) {
        this.service = service;
    }

    @GetMapping("/access")
    public AccessView get(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        return new AccessView(service.roles(sheet).stream().map(RoleView::of).toList(),
                settings(sheet));
    }

    @PutMapping("/roles")
    public RoleView setRole(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody RoleForm form) {
        Role role = Role.of(form.role()).orElseThrow();
        return RoleView.of(service.setRole(sheetId.trim(), user.trim(), form.user().trim(), role));
    }

    @PutMapping("/settings")
    public SettingsView setSettings(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody SettingsForm form) {
        String sheet = sheetId.trim();
        Map<String, String> gradeTerms = new LinkedHashMap<>();
        gradeTerms.put("A", form.termsA());
        gradeTerms.put("B", form.termsB());
        gradeTerms.put("C", form.termsC());
        service.setSettings(sheet, user.trim(), form.clerkLimit(), form.floor(), form.terms(), gradeTerms,
                form.latePct(), form.policyDate(), form.periodNumberingFrom());
        return settings(sheet);
    }

    private SettingsView settings(String sheet) {
        return SettingsView.of(service.clerkLimit(sheet), service.floor(sheet), service.terms(sheet),
                service.gradeTerms(sheet, "A"), service.gradeTerms(sheet, "B"), service.gradeTerms(sheet, "C"),
                service.latePct(sheet), service.policyDate(sheet), service.periodNumberingFrom(sheet));
    }

    /** A policy date or period numbering from that is no date on the calendar: the save is refused and changes nothing. */
    @ExceptionHandler(DateTimeParseException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void noSuchDate() {
    }

    /** Another user set a role for the same name at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void roleTaken() {
    }
}
