package net.officefloor.hq.app.role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.sheet.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
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

    /** What the screen shows of a role given to a user name. */
    public record RoleView(String user, String role) {
        static RoleView of(SheetRole role) {
            return new RoleView(role.getUserName(), role.getRole());
        }
    }

    /** The role a user acts in on the sheet. */
    public record MyRoleView(String role) {
    }

    /** The role form: a role of clerk, steward, viewer or restricted. */
    public record RoleForm(@NotNull @Pattern(regexp = "\\s*(clerk|steward|viewer|restricted)\\s*") String role) {
    }

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    /** The roles given on the sheet, or those in force at the end of the as-of day when one is given. */
    @GetMapping
    public List<RoleView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        String sheet = sheetId.trim();
        return (asOf == null ? service.list(sheet) : service.listOn(sheet, asOf)).stream().map(RoleView::of).toList();
    }

    /** The role the signed-in user acts in on the sheet now, or as they had it at the end of the
     *  as-of day when one is given. */
    @GetMapping("/me")
    public MyRoleView mine(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        String sheet = sheetId.trim();
        String name = user.trim();
        return new MyRoleView(asOf == null ? service.roleOf(sheet, name) : service.roleOn(sheet, name, asOf));
    }

    @PutMapping("/{userName}")
    public RoleView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String userName,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RoleForm form) {
        return RoleView.of(service.set(sheetId.trim(), user.trim(), userName.trim(), form.role().trim()));
    }
}
