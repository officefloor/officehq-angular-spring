package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import net.officefloor.hq.app.SignedInUser;
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
     *  batch, or null when it has had none. */
    public record SheetView(String id, LocalDate today, boolean archived, Integer lastSettleBatch) {
        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived(), sheet.getLastSettleBatch());
        }
    }

    /** A change of the sheet's today. */
    public record TodayChange(@NotNull LocalDate today) {
    }

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    /** Load a sheet by id, starting it empty if it was never loaded before. */
    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 100) String id) {
        return SheetView.of(service.load(id.trim()));
    }

    /** Change the sheet's today. */
    @PutMapping("/{id}/today")
    public SheetView setToday(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid TodayChange change) {
        return SheetView.of(service.setToday(id.trim(), SignedInUser.name(user), change.today()));
    }

    /** Archive the sheet; a steward only. */
    @PostMapping("/{id}/archive")
    public SheetView archive(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return SheetView.of(service.archive(id.trim(), SignedInUser.name(user)));
    }
}
