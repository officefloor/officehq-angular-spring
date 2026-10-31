package net.officefloor.hq.app.role;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import net.officefloor.hq.app.history.SheetHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Roles on a sheet, by user name. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/roles")
public class RoleController {

    /** A user name's assigned role as the screen shows it. */
    public record RoleView(String user, String role) {

        public static RoleView of(Role role) {
            return new RoleView(role.getUserName(), role.getRole());
        }
    }

    /** A role as the form submits it: clerk, steward or viewer. */
    public record RoleForm(@NotNull @Pattern(regexp = Role.CLERK + "|" + Role.STEWARD + "|" + Role.VIEWER) String role) {
    }

    private final RoleService roles;
    private final SheetHistory history;

    public RoleController(RoleService roles, SheetHistory history) {
        this.history = history;
        this.roles = roles;
    }

    @GetMapping
    public List<RoleView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.viewsAsOf(sheetId, asOf).roles();
        }
        return roles.list(sheetId).stream().map(RoleView::of).toList();
    }

    @PutMapping("/{userName}")
    public RoleView set(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S(.*\\S)?") String userName,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RoleForm request) {
        return RoleView.of(roles.set(sheetId, user, userName, request.role()));
    }
}
