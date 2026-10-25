package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
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
public class SettingController {

    /** What the screen shows of the sheet's settings; the clerk limit has two decimals, or is null
     *  when there is no limit; the floor has two decimals; the terms are the days after a claim's date
     *  it is due by; the late percent has two decimals; the policy date is null when blank (the sheet's
     *  today then). */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct, LocalDate policyDate,
            List<RoleView> roles) {
    }

    /** A role the sheet assigns a user name. */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole role) {
            return new RoleView(role.getUserName(), role.getRole().label());
        }
    }

    /** The clerk limit as entered; none means no limit. */
    public record ClerkLimitForm(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value) {
    }

    /** The floor as entered. */
    public record FloorForm(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value) {
    }

    /** The terms as entered: whole days. */
    public record TermsForm(@NotNull @Min(0) @Max(36500) Integer value) {
    }

    /** The late percent as entered. */
    public record LatePctForm(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal value) {
    }

    /** The policy date as entered; none means the sheet's today. */
    public record PolicyDateForm(LocalDate value) {
    }

    /** A role to assign a user name, as entered on the form. */
    public record RoleForm(@NotBlank @Size(max = 200) String user, @NotNull String role) {
    }

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public SettingsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String id = sheetId.trim();
        return view(id, service.clerkLimit(id), service.floor(id));
    }

    /** Set the sheet's clerk limit, or clear it. */
    @PutMapping("/settings/clerk-limit")
    public SettingsView setClerkLimit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ClerkLimitForm form) {
        String id = sheetId.trim();
        BigDecimal limit = service.setClerkLimit(id, SignedInUser.name(user), form.value());
        return view(id, limit, service.floor(id));
    }

    /** Set the sheet's floor: the least a claim's live due can be. */
    @PutMapping("/settings/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid FloorForm form) {
        String id = sheetId.trim();
        BigDecimal floor = service.setFloor(id, SignedInUser.name(user), form.value());
        return view(id, service.clerkLimit(id), floor);
    }

    /** Set the sheet's terms: the days after a claim's date it is due by. */
    @PutMapping("/settings/terms")
    public SettingsView setTerms(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid TermsForm form) {
        String id = sheetId.trim();
        service.setTerms(id, SignedInUser.name(user), form.value());
        return view(id, service.clerkLimit(id), service.floor(id));
    }

    /** Set the sheet's late percent: what an overdue claim gains of its figure for every full 30 days overdue. */
    @PutMapping("/settings/late-pct")
    public SettingsView setLatePct(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid LatePctForm form) {
        String id = sheetId.trim();
        service.setLatePct(id, SignedInUser.name(user), form.value());
        return view(id, service.clerkLimit(id), service.floor(id));
    }

    /** Set the sheet's policy date, or clear it: a claim dated before it rounds half up, on or after it half to even. */
    @PutMapping("/settings/policy-date")
    public SettingsView setPolicyDate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PolicyDateForm form) {
        String id = sheetId.trim();
        service.setPolicyDate(id, SignedInUser.name(user), form.value());
        return view(id, service.clerkLimit(id), service.floor(id));
    }

    /** Assign a user name a role on the sheet. */
    @PutMapping("/roles")
    public RoleView setRole(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid RoleForm form) {
        Role role = Role.of(form.role().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such role."));
        return RoleView.of(service.setRole(sheetId.trim(), SignedInUser.name(user), form.user().trim(), role));
    }

    private SettingsView view(String sheetId, BigDecimal limit, BigDecimal floor) {
        return new SettingsView(limit == null ? null : limit.toPlainString(), floor.toPlainString(),
                service.terms(sheetId), service.latePct(sheetId).toPlainString(),
                service.policyDate(sheetId), service.roles(sheetId).stream().map(RoleView::of).toList());
    }
}
