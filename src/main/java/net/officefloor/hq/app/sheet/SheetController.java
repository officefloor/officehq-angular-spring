package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import net.officefloor.hq.app.asof.AsOfReads;
import net.officefloor.hq.app.asof.AsOfService;
import net.officefloor.hq.app.session.SessionController;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.settings.ViewerPasses;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    /** A sheet as the screen shows it; lastBatch is the number of its last bulk settle batch, null for none. */
    public record SheetView(String id, LocalDate today, boolean archived, Integer lastBatch) {
        public static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived(),
                    sheet.getLastSettleBatch() == 0 ? null : sheet.getLastSettleBatch());
        }
    }

    public record TodayChange(@NotNull LocalDate today) {
    }

    /** The id of the sheet to clone this one to. */
    public record CloneForm(@NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String toSheetId) {
    }

    private final SheetService service;
    private final SheetCloneService clones;
    private final AsOfService asOf;
    private final SettingsService settings;

    public SheetController(SheetService service, SheetCloneService clones, AsOfService asOf,
            SettingsService settings) {
        this.service = service;
        this.clones = clones;
        this.asOf = asOf;
        this.settings = settings;
    }

    /** Load a sheet by id, starting it empty if it was never loaded before. */
    @PutMapping("/{id}")
    @ViewerPasses
    @AsOfReads
    public SheetView load(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String id) {
        return SheetView.of(service.load(id));
    }

    /** Change the sheet's today; refused for a role the permission matrix does not let change it. */
    @PutMapping("/{id}/today")
    public SheetView setToday(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String id,
            @Valid @RequestBody TodayChange change, @SessionAttribute(SessionController.USER) String user) {
        settings.refuseUnlessMay(id, user, "today");
        return SheetView.of(service.setToday(user, id, change.today()));
    }

    /** Clones the sheet to another id never used before; only a steward may. */
    @PostMapping("/{id}/clone")
    public void cloneSheet(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String id,
            @Valid @RequestBody CloneForm form, @SessionAttribute(SessionController.USER) String user) {
        clones.cloneSheet(user, id, form.toSheetId());
        // the request's own sheet is stored as it now stands once answered; the clone is new too
        asOf.capture(form.toSheetId());
    }
}
