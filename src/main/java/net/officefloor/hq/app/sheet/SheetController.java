package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sheet endpoints. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets")
@Validated
public class SheetController {

    /**
     * The sheet as shown to the user: its today, whether it is archived, whether the user is a viewer of it, and the
     * number of its last bulk settle batch (null while there has been none).
     */
    public record SheetView(String id, LocalDate today, boolean archived, boolean viewer, Long lastSettleBatch) {

        static SheetView of(Sheet sheet, boolean viewer) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived(), viewer,
                    sheet.getLastSettleBatch() == 0 ? null : sheet.getLastSettleBatch());
        }
    }

    public record TodayRequest(@NotNull LocalDate today) {
    }

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    /** Load a sheet by id; an id never used before starts an empty sheet. */
    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.load(id.trim(), user.trim()), user.trim());
    }

    /** Change the sheet's today. */
    @PutMapping("/{id}/today")
    public SheetView setToday(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TodayRequest request) {
        return view(service.setToday(id.trim(), user.trim(), request.today()), user.trim());
    }

    /** Archive the sheet: a steward may; every change to it is refused from then on. */
    @PostMapping("/{id}/archive")
    public SheetView archive(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.archive(id.trim(), user.trim()), user.trim());
    }

    private SheetView view(Sheet sheet, String user) {
        return SheetView.of(sheet, service.isViewer(sheet.getId(), user));
    }
}
