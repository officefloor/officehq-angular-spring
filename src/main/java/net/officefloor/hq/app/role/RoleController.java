package net.officefloor.hq.app.role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/roles")
@Validated
public class RoleController {

    /** What the screen is told about a role set on the sheet. */
    public record RoleView(String user, String role) {

        static RoleView of(Role role) {
            return new RoleView(role.getUserName(), role.getRole());
        }
    }

    /** A role as entered on the form: a user name and one of the roles. */
    public record RoleForm(
            @NotBlank @Size(max = 200) String user,
            @NotNull @Pattern(regexp = Role.CLERK + "|" + Role.STEWARD) String role) {
    }

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    /** The roles set on the sheet, by user name; a name not listed is a clerk. */
    @GetMapping
    public List<RoleView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return service.list(sheetId).stream().map(RoleView::of).toList();
    }

    /** Set a user name's role on the sheet. */
    @PutMapping
    public RoleView set(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid RoleForm form) {
        return RoleView.of(service.set(sheetId, SignedIn.name(user), form.user().trim(), form.role()));
    }
}
