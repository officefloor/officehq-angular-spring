package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A sheet's settings: the role each user name has, its clerk limit, its floor, its terms, its late percent, its policy date and its period numbering date. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
public class SettingController {

    /** The role is clerk or steward. */
    public record RoleForm(@NotBlank @Size(max = 100) String user, @NotNull String role) {}

    public record RoleView(String user, String role) {

        static RoleView of(SheetRole role) {
            return new RoleView(role.getUser(), role.getRole().code());
        }
    }

    /** The clerk limit has up to two decimals, 0.00 or more; blank means no limit. The floor has up to two
     * decimals, 0.00 or more. The terms are a whole number of days, 0 or more. The late percent has up to two
     * decimals, 0.00 or more. The policy date is a date (yyyy-mm-dd); blank means the sheet's today. The period numbering date is a date; blank means
     * off. A setting
     * left out (null) is left as it is. */
    public record SettingsForm(@Pattern(regexp = "|\\d{1,10}(\\.\\d{1,2})?") String clerkLimit,
            @Pattern(regexp = "\\d{1,10}(\\.\\d{1,2})?") String floor,
            @Pattern(regexp = "\\d{1,5}") String terms,
            @Pattern(regexp = "\\d{1,5}(\\.\\d{1,2})?") String latePct,
            @Pattern(regexp = "|\\d{4}-\\d{2}-\\d{2}") String policyDate,
            @Pattern(regexp = "|\\d{4}-\\d{2}-\\d{2}") String periodNumberingFrom) {}

    /** The clerk limit and floor are shown with two decimals; the clerk limit is null when there is none. The
     * terms are the days after its date a claim is due by. The late percent is shown with two decimals. The policy date is null when there is none (the sheet's
     * today is used). The period numbering date is null when it is off. */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, String policyDate,
            String periodNumberingFrom) {

        static SettingsView of(BigDecimal clerkLimit, BigDecimal floor, int terms, BigDecimal latePct,
                LocalDate policyDate, LocalDate periodNumberingFrom) {
            return new SettingsView(clerkLimit != null ? clerkLimit.setScale(2).toPlainString() : null,
                    floor.setScale(2).toPlainString(), terms, latePct.setScale(2).toPlainString(),
                    policyDate != null ? policyDate.toString() : null,
                    periodNumberingFrom != null ? periodNumberingFrom.toString() : null);
        }
    }

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    public List<RoleView> roles(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return service.roles(sheetId).stream().map(RoleView::of).toList();
    }

    @PutMapping("/roles")
    public RoleView setRole(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody RoleForm form) {
        Role role = Role.of(form.role().trim()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "A role is clerk or steward."));
        return RoleView.of(service.setRole(user, sheetId, form.user().trim(), role));
    }

    @GetMapping("/settings")
    public SettingsView settings(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return view(sheetId);
    }

    @PutMapping("/settings")
    public SettingsView save(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody SettingsForm form) {
        String limit = form.clerkLimit();
        Optional<BigDecimal> clerkLimit = limit == null ? null
                : limit.isEmpty() ? Optional.empty() : Optional.of(new BigDecimal(limit));
        Optional<LocalDate> policyDate = dateOrBlank(form.policyDate(), "A policy date is a date, or blank.");
        Optional<LocalDate> periodNumberingFrom = dateOrBlank(form.periodNumberingFrom(),
                "A period numbering date is a date, or blank.");
        service.save(user, sheetId, clerkLimit, form.floor() == null ? null : new BigDecimal(form.floor()),
                form.terms() == null ? null : Integer.valueOf(form.terms()),
                form.latePct() == null ? null : new BigDecimal(form.latePct()), policyDate, periodNumberingFrom);
        return view(sheetId);
    }

    private SettingsView view(String sheetId) {
        return SettingsView.of(service.clerkLimit(sheetId), service.floor(sheetId), service.terms(sheetId),
                service.latePct(sheetId), service.policyDate(sheetId), service.periodNumberingFrom(sheetId));
    }

    /** A date setting as given: null when left out, empty when blank. */
    private static Optional<LocalDate> dateOrBlank(String date, String refusal) {
        try {
            return date == null ? null : date.isEmpty() ? Optional.empty() : Optional.of(LocalDate.parse(date));
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, refusal);
        }
    }
}
