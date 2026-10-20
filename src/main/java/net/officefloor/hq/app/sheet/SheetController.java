package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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

    public record SheetView(String id, LocalDate today, boolean archived) {

        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived());
        }
    }

    private final SheetService service;
    private final TodayService todays;
    private final ArchiveService archives;

    public SheetController(SheetService service, TodayService todays, ArchiveService archives) {
        this.service = service;
        this.todays = todays;
        this.archives = archives;
    }

    /** Load a sheet by id, starting it empty when it was never loaded before. */
    @PostMapping("/load")
    public SheetView load(@RequestHeader("X-User") String user, @Valid @RequestBody LoadSheet request) {
        return SheetView.of(service.load(request.id(), user));
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
