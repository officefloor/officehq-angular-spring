package net.officefloor.hq.app.role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** The roles the sheet gives user names. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/roles")
public class RoleController {

    public record RoleView(String user, String role) {

        static RoleView of(SheetRole role) {
            return new RoleView(role.getUserName(), role.getRole());
        }
    }

    public record RoleRequest(@NotBlank @Size(max = 200) String user,
            @NotBlank @Pattern(regexp = "clerk|steward|viewer") String role) {
    }

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    /** The names given a role on the sheet, by name; any other name is a clerk. */
    @GetMapping
    public List<RoleView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return service.list(sheetId.trim()).stream().map(RoleView::of).toList();
    }

    /** Give a user name a role: clerk, steward or viewer. */
    @PutMapping
    public RoleView set(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid RoleRequest request) {
        return RoleView.of(service.set(sheetId.trim(), user.trim(), request.user().trim(), request.role()));
    }
}
