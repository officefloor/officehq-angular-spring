package net.officefloor.hq.app.role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing and assigning the roles a sheet gives user names. */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    public record ListRolesRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record SetRoleRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = Role.CLERK + "|" + Role.STEWARD) String role) {
    }

    public record RoleResponse(String user, String role) {
    }

    private static RoleResponse response(Role role) {
        return new RoleResponse(role.getUserName(), role.getRole());
    }

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<RoleResponse> list(@Valid @RequestBody ListRolesRequest req) {
        return service.list(req.sheet().trim()).stream().map(RoleController::response).toList();
    }

    @PostMapping("/set")
    public RoleResponse set(@Valid @RequestBody SetRoleRequest req) {
        return response(service.set(req.sheet().trim(), req.user().trim(), req.name().trim(), req.role()));
    }
}
