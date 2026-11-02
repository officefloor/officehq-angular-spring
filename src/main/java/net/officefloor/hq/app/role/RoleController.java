package net.officefloor.hq.app.role;

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

    /** The role form: a role of clerk, steward or viewer. */
    public record RoleForm(@NotNull @Pattern(regexp = "\\s*(clerk|steward|viewer)\\s*") String role) {
    }

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    @GetMapping
    public List<RoleView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(RoleView::of).toList();
    }

    @PutMapping("/{userName}")
    public RoleView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String userName,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RoleForm form) {
        return RoleView.of(service.set(sheetId.trim(), user.trim(), userName.trim(), form.role().trim()));
    }
}
