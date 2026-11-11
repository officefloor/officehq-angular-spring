package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.user.UserLocaleService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in (a name, no password), loading a sheet by id, changing a sheet's today, archiving it and cloning it. */
@RestController
@RequestMapping("/api")
public class SheetController {

    public record SignInRequest(@NotBlank @Size(max = 100) String name) {
    }

    /** {@code locale}: how the screen shows the user figures (see {@link UserLocaleService}). */
    public record SignInResponse(String name, String locale) {
    }

    /** {@code open}: the sheet the user has open, whose today a sheet never loaded starts with; none for none. */
    public record LoadSheetRequest(@NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 100) String user,
            @Size(max = 100) String open) {
    }

    public record SetTodayRequest(@NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 100) String user,
            @NotNull LocalDate today) {
    }

    public record ArchiveSheetRequest(@NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 100) String user) {
    }

    public record CloneSheetRequest(@NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 100) String user,
            @NotBlank @Size(max = 100) String to) {
    }

    /**
     * {@code lastBatch}: the number of the sheet's last settle batch; none when there has been none.
     * {@code grades}: the sheet's grades, in alphabetical order.
     */
    public record SheetResponse(String id, LocalDate today, boolean archived, Integer lastBatch, List<String> grades) {
    }

    private static SheetResponse response(Sheet sheet) {
        return new SheetResponse(sheet.getId(), sheet.getToday(), sheet.isArchived(),
                sheet.getLastBatch() == 0 ? null : sheet.getLastBatch(), sheet.gradeList());
    }

    private final SheetService service;
    private final RoleService roles;
    private final SheetCloneService clones;
    private final UserLocaleService locales;

    public SheetController(SheetService service, RoleService roles, SheetCloneService clones,
            UserLocaleService locales) {
        this.service = service;
        this.roles = roles;
        this.clones = clones;
        this.locales = locales;
    }

    @PostMapping("/signin")
    public SignInResponse signIn(@Valid @RequestBody SignInRequest req) {
        String name = req.name().trim();
        return new SignInResponse(name, locales.localeOf(name));
    }

    @PostMapping("/sheets/load")
    public SheetResponse load(@Valid @RequestBody LoadSheetRequest req) {
        return response(service.load(req.id().trim(), req.user().trim(),
                req.open() == null || req.open().isBlank() ? null : req.open().trim()));
    }

    @PostMapping("/sheets/today")
    public SheetResponse setToday(@Valid @RequestBody SetTodayRequest req) {
        // a viewer may not change the sheet's today, nor a role the permission matrix leaves out
        // (checked here: the role service depends on the sheet service)
        roles.refuseViewer(req.id().trim(), req.user().trim());
        roles.refuseUnlessPermitted(req.id().trim(), req.user().trim(), "today");
        return response(service.setToday(req.id().trim(), req.user().trim(), req.today()));
    }

    @PostMapping("/sheets/archive")
    public SheetResponse archive(@Valid @RequestBody ArchiveSheetRequest req) {
        // only a steward may archive the sheet (checked here: the role service depends on the sheet service)
        roles.refuseUnlessSteward(req.id().trim(), req.user().trim());
        return response(service.archive(req.id().trim(), req.user().trim()));
    }

    /** Clone the sheet to another id never used; answers the clone. */
    @PostMapping("/sheets/clone")
    public SheetResponse cloneSheet(@Valid @RequestBody CloneSheetRequest req) {
        return response(clones.cloneTo(req.id().trim(), req.user().trim(), req.to().trim()));
    }
}
