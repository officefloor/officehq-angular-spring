package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets")
public class SheetController {

    public record LoadSheet(@NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String id) {}

    public record SetToday(@NotNull LocalDate today) {}

    /** A loaded sheet also shows the role the user loading it has on it ("clerk", "steward" or "viewer"); no
     * role otherwise. */
    public record SheetView(String id, LocalDate today, boolean archived, String role) {

        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived(), null);
        }
    }

    private final SheetService service;
    private final TodayService todays;
    private final ArchiveService archives;
    private final SettingService settings;

    public SheetController(SheetService service, TodayService todays, ArchiveService archives,
            SettingService settings) {
        this.service = service;
        this.todays = todays;
        this.archives = archives;
        this.settings = settings;
    }

    /** Load a sheet by id, starting it empty when it was never loaded before. */
    @PostMapping("/load")
    public SheetView load(@RequestHeader("X-User") String user, @Valid @RequestBody LoadSheet request) {
        Sheet sheet = service.load(request.id(), user);
        return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived(),
                settings.roleOf(sheet.getId(), user.trim()).code());
    }

    /** Change the sheet's today. */
    @PutMapping("/{id}/today")
    public SheetView setToday(@RequestHeader("X-User") String user, @PathVariable String id,
            @Valid @RequestBody SetToday request) {
        return SheetView.of(todays.setToday(user, id, request.today()));
    }

    /** Archive the sheet; only a steward may. */
    @PostMapping("/{id}/archive")
    public SheetView archive(@RequestHeader("X-User") String user, @PathVariable String id) {
        return SheetView.of(archives.archive(user, id));
    }
}
