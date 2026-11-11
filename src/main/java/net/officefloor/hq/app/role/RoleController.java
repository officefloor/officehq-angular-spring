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

/** The roles of the user names on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/roles")
@Validated
public class RoleController {

    public record RoleView(String user, String role) {

        static RoleView of(SheetRole role) {
            return new RoleView(role.getUserName(), role.getRole().value());
        }
    }

    /** A user name and its role: clerk or steward. */
    public record RoleRequest(@NotBlank @Size(max = 100) String user,
            @NotNull @Pattern(regexp = "\\s*(clerk|steward)\\s*") String role) {
    }

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    /** The roles set on the sheet, by user name; a name not listed is a clerk. */
    @GetMapping
    public List<RoleView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(RoleView::of).toList();
    }

    @PutMapping
    public RoleView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RoleRequest request) {
        return RoleView.of(service.set(sheetId.trim(), user.trim(), request.user().trim(),
                Role.of(request.role().trim())));
    }
}
