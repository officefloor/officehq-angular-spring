package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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

/** A sheet's settings: the role each user name has, its clerk limit, its floor and its terms. */
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
     * decimals, 0.00 or more. The terms are a whole number of days, 0 or more. A setting left out (null) is
     * left as it is. */
    public record SettingsForm(@Pattern(regexp = "|\\d{1,10}(\\.\\d{1,2})?") String clerkLimit,
            @Pattern(regexp = "\\d{1,10}(\\.\\d{1,2})?") String floor,
            @Pattern(regexp = "\\d{1,5}") String terms) {}

    /** The clerk limit and floor are shown with two decimals; the clerk limit is null when there is none. The
     * terms are the days after its date a claim is due by. */
    public record SettingsView(String clerkLimit, String floor, int terms) {

        static SettingsView of(BigDecimal clerkLimit, BigDecimal floor, int terms) {
            return new SettingsView(clerkLimit != null ? clerkLimit.setScale(2).toPlainString() : null,
                    floor.setScale(2).toPlainString(), terms);
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
        return SettingsView.of(service.clerkLimit(sheetId), service.floor(sheetId), service.terms(sheetId));
    }

    @PutMapping("/settings")
    public SettingsView save(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody SettingsForm form) {
        String limit = form.clerkLimit();
        Optional<BigDecimal> clerkLimit = limit == null ? null
                : limit.isEmpty() ? Optional.empty() : Optional.of(new BigDecimal(limit));
        service.save(user, sheetId, clerkLimit, form.floor() == null ? null : new BigDecimal(form.floor()),
                form.terms() == null ? null : Integer.valueOf(form.terms()));
        return SettingsView.of(service.clerkLimit(sheetId), service.floor(sheetId), service.terms(sheetId));
    }
}
