package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.SheetAsOf;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.rollup.ConsolidatedService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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

    /** A sheet, with the number of its last settle batch (null while it has had none) and the id of its
     * parent sheet as it stands (null when it has none). */
    public record SheetView(String id, LocalDate today, boolean archived, Integer lastSettleBatch, String parent) {
        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived(), sheet.getLastSettleBatch(),
                    sheet.getParentId());
        }
    }

    public record TodayRequest(@NotNull LocalDate today) {
    }

    public record CloneRequest(@NotNull @Size(max = 200) String to) {
    }

    private final SheetService service;

    private final SheetHistory history;

    private final ConsolidatedService consolidated;

    private final SheetCloneService cloning;

    public SheetController(SheetService service, SheetHistory history, ConsolidatedService consolidated,
            SheetCloneService cloning) {
        this.service = service;
        this.history = history;
        this.consolidated = consolidated;
        this.cloning = cloning;
    }

    /** The figures of the sheet's descendants as they stand, added up (see {@link ConsolidatedService});
     * while the sheet is read as it stood on an earlier date, the sheet's own figures as they stand now
     * are added in too, the consolidated figures not following that date. */
    @GetMapping("/{id}/descendants")
    public ConsolidatedService.Figures descendants(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return asOf == null ? consolidated.descendantsOf(id.trim()) : consolidated.consolidatedOf(id.trim());
    }

    /** Load a sheet by id, starting it empty if it has never been loaded; as it stood at the end of
     * {@code asOf}, that date standing in for its today, when one is given. */
    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        Sheet sheet = service.load(id.trim(), user.trim());
        if (asOf == null) return SheetView.of(sheet);
        SheetAsOf then = history.read(sheet.getId(), asOf);
        return new SheetView(sheet.getId(), asOf, then.archived(), then.lastSettleBatch(), sheet.getParentId());
    }

    /** Change the sheet's today. */
    @PutMapping("/{id}/today")
    public SheetView setToday(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TodayRequest request) {
        return SheetView.of(service.setToday(id.trim(), user.trim(), request.today()));
    }

    /** Archive the sheet: only a steward may. */
    @PostMapping("/{id}/archive")
    public SheetView archive(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return SheetView.of(service.archive(id.trim(), user.trim()));
    }

    /** Clone the sheet to another id, one never used: only a steward may. Returns the clone. */
    @PostMapping("/{id}/clone")
    public SheetView cloneSheet(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CloneRequest request) {
        return SheetView.of(cloning.cloneSheet(id.trim(), user.trim(), request.to().trim()));
    }
}
