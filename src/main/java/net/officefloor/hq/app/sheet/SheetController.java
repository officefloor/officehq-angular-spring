package net.officefloor.hq.app.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    public record SheetView(String id, LocalDate today, boolean archived, Long lastBatch) {
        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived(), sheet.getLastBatchNumber());
        }
    }

    /** The figures of a sheet's descendants added up, each as that sheet shows it now, and the sheet's
     *  own figures as it shows them now (whatever day a screen is set to show it as of). */
    public record DescendantFigures(int sheets, int people, String dueTotal, String overdueTotal, int ownPeople,
            String ownDueTotal, String ownOverdueTotal) {
    }

    public record TodayRequest(@NotNull LocalDate today) {
    }

    private final SheetService service;
    private final RollupService rollups;
    private final PeriodService periods;
    private final SettingService settings;
    private final ClaimService claims;

    public SheetController(SheetService service, RollupService rollups, PeriodService periods, SettingService settings,
            ClaimService claims) {
        this.service = service;
        this.claims = claims;
        this.rollups = rollups;
        this.periods = periods;
        this.settings = settings;
    }

    /** Load a sheet by id, starting it empty if it was never loaded before. */
    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 200) String id) {
        return SheetView.of(service.load(id.trim()));
    }

    /** The sheet's descendants' figures added up: how many people are on them, and each one's
     *  due-total and overdue-total as that sheet shows it. The sheet's own figures now are given apart. */
    @GetMapping("/{id}/descendant-figures")
    public DescendantFigures descendantFigures(@PathVariable @NotBlank @Size(max = 200) String id) {
        int people = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        var descendants = service.descendants(id.trim());
        for (String sheet : descendants) {
            RollupService.Headline figures = rollups.headline(sheet);
            people += figures.people();
            due = due.add(figures.dueTotal());
            overdue = overdue.add(figures.overdueTotal());
        }
        RollupService.Headline own = rollups.headline(id.trim());
        return new DescendantFigures(descendants.size(), people, due.toPlainString(), overdue.toPlainString(),
                own.people(), own.dueTotal().toPlainString(), own.overdueTotal().toPlainString());
    }

    /** Change the sheet's today, closing the periods past their grace days and releasing every claim
     *  editor's lock when the date moved; the audit records each person's due-total on the day being
     *  left when the date moved, then only the figures it moved. */
    @PutMapping("/{id}/today")
    public SheetView setToday(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated TodayRequest request) {
        String sheet = id.trim();
        String by = SignedInUser.decode(user);
        return SheetView.of(rollups.track(sheet, by,
                () -> {
                    LocalDate was = service.today(sheet);
                    if (!was.equals(request.today())) {
                        rollups.snapshot(sheet, by);
                    }
                    Sheet changed = service.setToday(sheet, request.today());
                    if (!was.equals(changed.getToday())) {
                        claims.releaseLocks(sheet);
                    }
                    periods.autoClose(sheet, by);
                    return changed;
                }));
    }

    /** Archive the sheet: only a steward may, and only once; from then on every change is refused. */
    @PostMapping("/{id}/archive")
    public SheetView archive(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        String sheet = id.trim();
        String by = SignedInUser.decode(user);
        if (settings.roleOf(sheet, by) != Role.STEWARD) {
            throw new ArchiveRefusedException("only a steward may archive the sheet");
        }
        return SheetView.of(service.archive(sheet, by));
    }

    /** A date that is not a real yyyy-mm-dd date is refused. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
