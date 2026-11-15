package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.settings.SettingsController.DatedValue;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.settings.ViewerAllowed;
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
     *  by key, each key's by from-date; and whether it is archived. */
    public record SheetView(String id, LocalDate today, String floor, int terms, String latePct, LocalDate policyDate,
            String cap, Map<String, List<DatedValue>> dated, boolean archived) {
        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.getFloor().toPlainString(), sheet.getTerms(),
                    sheet.getLatePct().setScale(2).toPlainString(), sheet.getPolicyDate(),
                    sheet.getCap() == null ? null : sheet.getCap().setScale(2).toPlainString(),
                    DatedValue.of(sheet.getDatedSettings()), sheet.isArchived());
        }
    }

    public record TodayChange(@NotNull LocalDate today) {
    }

    private final SheetService service;
    private final SettingsService settings;

    public SheetController(SheetService service, SettingsService settings) {
        this.service = service;
        this.settings = settings;
    }

    @PostMapping("/{id}/load")
    @ViewerAllowed
    public SheetView load(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return SheetView.of(service.load(id, user.trim()));
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
}
