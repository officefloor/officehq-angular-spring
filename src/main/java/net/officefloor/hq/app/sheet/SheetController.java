package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
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
     * The sheet as shown to the user: its today, whether it is archived, whether the user is a viewer of it, the
     * number of its last bulk settle batch (null while there has been none), and whether the user is a steward of it
     * (in their own right or by a delegation covering its today), and, for a restricted user, the ids of the people
     * the people list shows them (null: everyone).
     */
    public record SheetView(String id, LocalDate today, boolean archived, boolean viewer, Long lastSettleBatch,
            boolean steward, List<Long> visiblePeople) {

        static SheetView of(Sheet sheet, boolean viewer, boolean steward, List<Long> visiblePeople) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived(), viewer,
                    sheet.getLastSettleBatch() == 0 ? null : sheet.getLastSettleBatch(), steward, visiblePeople);
        }
    }

    public record TodayRequest(@NotNull LocalDate today) {
    }

    public record CloneRequest(String to) {
    }

    private final SheetService service;
    private final SheetCloneService clones;

    public SheetController(SheetService service, SheetCloneService clones) {
        this.service = service;
        this.clones = clones;
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

    /** Clone the sheet to another id never used: a steward may. Gives this sheet as it is. */
    @PostMapping("/{id}/clone")
    public SheetView cloneTo(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody CloneRequest request) {
        clones.cloneTo(id.trim(), user.trim(), request.to());
        return view(service.get(id.trim()), user.trim());
    }

    private SheetView view(Sheet sheet, String user) {
        return SheetView.of(sheet, service.isViewer(sheet.getId(), user), service.isSteward(sheet.getId(), user),
                service.visiblePeople(sheet.getId(), user));
    }
}
