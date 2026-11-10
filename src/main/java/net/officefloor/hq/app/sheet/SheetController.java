package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import net.officefloor.hq.app.role.RoleService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in (a name, no password), loading a sheet by id, changing a sheet's today and archiving it. */
@RestController
@RequestMapping("/api")
public class SheetController {

    public record SignInRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record SignInResponse(String name) {
    }

    public record LoadSheetRequest(@NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 100) String user) {
    }

    public record SetTodayRequest(@NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 100) String user,
            @NotNull LocalDate today) {
    }

    public record ArchiveSheetRequest(@NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 100) String user) {
    }

    /** {@code lastBatch}: the number of the sheet's last settle batch; none when there has been none. */
    public record SheetResponse(String id, LocalDate today, boolean archived, Integer lastBatch) {
    }

    private static SheetResponse response(Sheet sheet) {
        return new SheetResponse(sheet.getId(), sheet.getToday(), sheet.isArchived(),
                sheet.getLastBatch() == 0 ? null : sheet.getLastBatch());
    }

    private final SheetService service;
    private final RoleService roles;

    public SheetController(SheetService service, RoleService roles) {
        this.service = service;
        this.roles = roles;
    }

    @PostMapping("/signin")
    public SignInResponse signIn(@Valid @RequestBody SignInRequest req) {
        return new SignInResponse(req.name().trim());
    }

    @PostMapping("/sheets/load")
    public SheetResponse load(@Valid @RequestBody LoadSheetRequest req) {
        return response(service.load(req.id().trim(), req.user().trim()));
    }

    @PostMapping("/sheets/today")
    public SheetResponse setToday(@Valid @RequestBody SetTodayRequest req) {
        // a viewer may not change the sheet's today (checked here: the role service depends on the sheet service)
        roles.refuseViewer(req.id().trim(), req.user().trim());
        return response(service.setToday(req.id().trim(), req.user().trim(), req.today()));
    }

    @PostMapping("/sheets/archive")
    public SheetResponse archive(@Valid @RequestBody ArchiveSheetRequest req) {
        // only a steward may archive the sheet (checked here: the role service depends on the sheet service)
        roles.refuseUnlessSteward(req.id().trim(), req.user().trim());
        return response(service.archive(req.id().trim(), req.user().trim()));
    }
}
