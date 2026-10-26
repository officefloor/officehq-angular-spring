package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets")
@Validated
public class SheetController {

    /** What the screen shows of a sheet. Last settle batch is the number of its last bulk settle
     *  batch, or null when it has had none. Role is the role the signed-in user acts in on the sheet
     *  (a clerk when no one is signed in). */
    public record SheetView(String id, LocalDate today, boolean archived, Integer lastSettleBatch, String role) {
        static SheetView of(Sheet sheet, Role role) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived(), sheet.getLastSettleBatch(),
                    role.label());
        }
    }

    /** A change of the sheet's today. */
    public record TodayChange(@NotNull LocalDate today) {
    }

    /** A clone of the sheet to another id. */
    public record CloneRequest(@NotBlank @Size(max = 100) String to) {
    }

    /** What a clone made: the id of the sheet it made. */
    public record CloneView(String to) {
    }

    private final SheetService service;
    private final CloneService clones;
    private final SettingService settings;

    public SheetController(SheetService service, CloneService clones, SettingService settings) {
        this.service = service;
        this.clones = clones;
        this.settings = settings;
    }

    private SheetView view(Sheet sheet, String user) {
        Role role = user == null || user.isBlank() ? Role.CLERK : settings.roleOf(sheet.getId(), SignedInUser.name(user));
        return SheetView.of(sheet, role);
    }

    /** Load a sheet by id, starting it empty if it was never loaded before. */
    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader(value = SignedInUser.HEADER, required = false) String user) {
        return view(service.load(id.trim()), user);
    }

    /** Change the sheet's today. */
    @PutMapping("/{id}/today")
    public SheetView setToday(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid TodayChange change) {
        return view(service.setToday(id.trim(), SignedInUser.name(user), change.today()), user);
    }

    /** Archive the sheet; a steward only. */
    @PostMapping("/{id}/archive")
    public SheetView archive(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return view(service.archive(id.trim(), SignedInUser.name(user)), user);
    }

    /** Clone the sheet to another id that was never used; a steward only. */
    @PostMapping("/{id}/clone")
    public CloneView cloneSheet(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid CloneRequest request) {
        String to = request.to().trim();
        clones.cloneTo(id.trim(), SignedInUser.name(user), to);
        return new CloneView(to);
    }
}
