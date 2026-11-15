package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.history.AsOfAllowed;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.settings.SettingsController.DatedValue;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.settings.ViewerAllowed;
import org.springframework.format.annotation.DateTimeFormat;
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

    /** A sheet as the screen takes it: its id, its today, its floor with two decimals, its
     *  terms in days, its late percent with two decimals, its policy date (null when blank), and its cap with two decimals (null when none),
     *  the dated ones as in force on its today; and every value recorded for its dated settings,
     *  by key, each key's by from-date; whether it is archived; and its parent sheet's id (null when
     *  none), as it is now even when the sheet is shown as of a date; and its proof kinds, comma
     *  separated (null when none). */
    public record SheetView(String id, LocalDate today, String floor, int terms, String latePct, LocalDate policyDate,
            String cap, Map<String, List<DatedValue>> dated, boolean archived, String parent, String proofKinds) {
        static SheetView of(Sheet sheet) {
            return of(sheet, sheet.getParent());
        }

        static SheetView of(Sheet sheet, String parent) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.getFloor().toPlainString(), sheet.getTerms(),
                    sheet.getLatePct().setScale(2).toPlainString(), sheet.getPolicyDate(),
                    sheet.getCap() == null ? null : sheet.getCap().setScale(2).toPlainString(),
                    DatedValue.of(sheet.getDatedSettings()), sheet.isArchived(), parent, sheet.getProofKinds());
        }
    }

    public record TodayChange(@NotNull LocalDate today) {
    }

    /** A clone as the screen submits it: the id of the sheet to clone this one to. */
    public record CloneForm(@NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String toSheetId) {
    }

    private final SheetService service;
    private final SettingsService settings;
    private final HistoryService history;
    private final SheetCloneService cloning;

    public SheetController(SheetService service, SettingsService settings, HistoryService history,
            SheetCloneService cloning) {
        this.service = service;
        this.settings = settings;
        this.history = history;
        this.cloning = cloning;
    }

    /** Load the sheet; shown as of a date, it is as it stood at the end of that date, that date its today. */
    @PostMapping("/{id}/load")
    @ViewerAllowed
    @AsOfAllowed
    public SheetView load(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        Sheet sheet = service.load(id, user.trim());
        return SheetView.of(asOf == null ? sheet : history.asOf(id, asOf).sheet(), sheet.getParent());
    }

    @PutMapping("/{id}/today")
    public SheetView setToday(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TodayChange body) {
        return SheetView.of(service.setToday(id, user.trim(), body.today()));
    }

    /** Archive the sheet: a steward only; everything stays visible and every change is refused. */
    @PostMapping("/{id}/archive")
    public SheetView archive(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return SheetView.of(settings.archive(id, user.trim()));
    }

    /** Clone the sheet to another id that was never used: a steward only; answers with the clone. */
    @PostMapping("/{id}/clone")
    public SheetView cloneSheet(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CloneForm body) {
        return SheetView.of(cloning.cloneSheet(id, user.trim(), body.toSheetId()));
    }
}
