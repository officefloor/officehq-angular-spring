package net.officefloor.hq.app.role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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

    public record MyRoleRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user) {
    }

    public record SetRoleRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = Role.CLERK + "|" + Role.STEWARD + "|" + Role.VIEWER) String role) {
    }

    public record RoleResponse(String user, String role) {
    }

    public record DelegateRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotBlank @Size(max = 100) String name, LocalDate from, @NotNull LocalDate to) {
    }

    public record DelegationResponse(Long id, String user, String from, String to, String steward) {
    }

    private static DelegationResponse response(Delegation d) {
        return new DelegationResponse(d.getId(), d.getUserName(), d.getFromDate().toString(),
                d.getToDate().toString(), d.getSteward());
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

    /** The signed-in user's role on the sheet (a delegation in force counts); as of a date, the role they had then. */
    @PostMapping("/mine/list")
    public RoleResponse mine(@Valid @RequestBody MyRoleRequest req) {
        String user = req.user().trim();
        return new RoleResponse(user, service.roleOf(req.sheet().trim(), user));
    }

    @PostMapping("/delegations")
    public List<DelegationResponse> delegations(@Valid @RequestBody ListRolesRequest req) {
        return service.delegations(req.sheet().trim()).stream().map(RoleController::response).toList();
    }

    @PostMapping("/delegate")
    public DelegationResponse delegate(@Valid @RequestBody DelegateRequest req) {
        return response(service.delegate(req.sheet().trim(), req.user().trim(), req.name().trim(), req.from(),
                req.to()));
    }

    @PostMapping("/set")
    public RoleResponse set(@Valid @RequestBody SetRoleRequest req) {
        return response(service.set(req.sheet().trim(), req.user().trim(), req.name().trim(), req.role()));
    }
}
