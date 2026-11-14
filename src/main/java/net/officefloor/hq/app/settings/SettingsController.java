package net.officefloor.hq.app.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class SettingsController {

    /** The sheet's settings as the screen shows them: the clerk limit with two decimals, null for
     *  none, the floor with two decimals, the terms in days, and the late percent with two decimals. */
    public record SettingsView(String clerkLimit, String floor, int terms, String latePct) {
        static SettingsView of(BigDecimal clerkLimit, BigDecimal floor, int terms, BigDecimal latePct) {
            return new SettingsView(clerkLimit == null ? null : clerkLimit.toPlainString(), floor.toPlainString(), terms,
                    latePct.setScale(2).toPlainString());
        }
    }

    /** The clerk limit as the form submits it: 0.00 or more with up to two decimals, blank for no limit. */
    public record ClerkLimitForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value) {
    }

    /** The floor as the form submits it: 0.00 or more with up to two decimals, blank for the default. */
    public record FloorForm(@NotNull @Pattern(regexp = "(\\d{1,13}(\\.\\d{1,2})?)?") String value) {
    }

    /** The terms as the form submits them: a whole number of days, blank for the default. */
    public record TermsForm(@NotNull @Pattern(regexp = "(\\d{1,4})?") String value) {
    }

    /** The late percent as the form submits it: 0.00 or more with up to two decimals, blank for the default. */
    public record LatePctForm(@NotNull @Pattern(regexp = "(\\d{1,3}(\\.\\d{1,2})?)?") String value) {
    }

    /** A user name's role on the sheet. */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole r) {
            return new RoleView(r.getUser(), r.getRole().key());
        }
    }

    /** A role as the form submits it: a user name without spaces and clerk or steward. */
    public record RoleForm(
            @NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String user,
            @NotNull @Pattern(regexp = "clerk|steward") String role) {
    }

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
        service.setClerkLimit(sheetId, user.trim(), limit);
        return view(sheetId);
    }

    @PutMapping("/settings/floor")
    public SettingsView setFloor(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FloorForm body) {
        BigDecimal floor = body.value().isEmpty() ? Sheet.DEFAULT_FLOOR : new BigDecimal(body.value());
        service.setFloor(sheetId, user.trim(), floor);
        return view(sheetId);
    }

    @PutMapping("/settings/terms")
    public SettingsView setTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsForm body) {
        int terms = body.value().isEmpty() ? Sheet.DEFAULT_TERMS : Integer.parseInt(body.value());
        service.setTerms(sheetId, user.trim(), terms);
        return view(sheetId);
    }

    @PutMapping("/settings/late-pct")
    public SettingsView setLatePct(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody LatePctForm body) {
        BigDecimal latePct = body.value().isEmpty() ? Sheet.DEFAULT_LATE_PCT : new BigDecimal(body.value());
        service.setLatePct(sheetId, user.trim(), latePct);
        return view(sheetId);
    }

    private SettingsView view(String sheetId) {
        return SettingsView.of(service.clerkLimit(sheetId), service.floor(sheetId), service.terms(sheetId),
                service.latePct(sheetId));
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
