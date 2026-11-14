package net.officefloor.hq.app.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.AsOf;
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

    /** The figures of a sheet's descendants added up, each as that sheet shows it, and the sheet's own
     *  figures: as they are now, or as they stood at the end of the screen's as-of date. Per grade, the
     *  descendants' due-totals of the people on it not retired; per status, their claims with it; and
     *  how many of their claims that count are overdue. */
    public record DescendantFigures(int sheets, int people, String dueTotal, String overdueTotal, int ownPeople,
            String ownDueTotal, String ownOverdueTotal, Map<String, String> gradeDueTotals,
            Map<String, Integer> statusCounts, List<PersonFiguresView> personFigures, int overdueCount) {
    }

    /** A descendant's person as that sheet shows them: their name and aliases, their due-total (null
     *  when they have none) and their overdue-total. */
    public record PersonFiguresView(String name, List<String> aliases, String dueTotal, String overdueTotal) {
    }

    /** A sheet's figures per grade as its summary shows them: whether the sheet was ever used, and per
     *  grade the due-totals of the people on it not retired added up. */
    public record GradeDueTotals(boolean exists, Map<String, String> gradeDueTotals) {
    }

    public record TodayRequest(@NotNull LocalDate today) {
    }

    public record CloneRequest(String to) {
    }

    /** The sheet a clone was made to. */
    public record CloneView(String to) {
    }

    private final SheetService service;
    private final RollupService rollups;
    private final PeriodService periods;
    private final SettingService settings;
    private final ClaimService claims;
    private final SheetCloneService clones;

    public SheetController(SheetService service, RollupService rollups, PeriodService periods, SettingService settings,
            ClaimService claims, SheetCloneService clones) {
        this.service = service;
        this.clones = clones;
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
     *  due-total and overdue-total as that sheet shows it. The sheet's own figures are given apart.
     *  While the screen is set to an as-of date, every sheet's figures are as it stood at the end of
     *  that day, the date standing in for the sheet's today. */
    @GetMapping("/{id}/descendant-figures")
    public DescendantFigures descendantFigures(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader(value = AsOf.HEADER, required = false) String asOfHeader) {
        LocalDate asOf = AsOf.parse(asOfHeader);
        int people = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        int overdueCount = 0;
        Map<String, BigDecimal> gradeDues = new TreeMap<>();
        Map<String, Integer> statusCounts = new TreeMap<>();
        List<PersonFiguresView> personFigures = new ArrayList<>();
        var descendants = service.descendants(id.trim());
        for (String sheet : descendants) {
            rollups.personFigures(sheet, asOf).forEach(p -> personFigures.add(new PersonFiguresView(p.name(),
                    p.aliases(), p.dueTotal() == null ? null : p.dueTotal().toPlainString(),
                    p.overdueTotal().toPlainString())));
            RollupService.Headline figures = rollups.headline(sheet, asOf);
            people += figures.people();
            due = due.add(figures.dueTotal());
            overdue = overdue.add(figures.overdueTotal());
            overdueCount += figures.overdueCount();
            RollupService.Breakdown breakdown = rollups.breakdown(sheet, asOf);
            breakdown.gradeDueTotals().forEach((grade, total) -> gradeDues.merge(grade, total, BigDecimal::add));
            breakdown.statusCounts().forEach((status, count) -> statusCounts.merge(status, count, Integer::sum));
        }
        Map<String, String> gradeDueTotals = new TreeMap<>();
        gradeDues.forEach((grade, total) -> gradeDueTotals.put(grade, total.toPlainString()));
        RollupService.Headline own = rollups.headline(id.trim(), asOf);
        return new DescendantFigures(descendants.size(), people, due.toPlainString(), overdue.toPlainString(),
                own.people(), own.dueTotal().toPlainString(), own.overdueTotal().toPlainString(), gradeDueTotals,
                statusCounts, personFigures, overdueCount);
    }

    /** The sheet's due-total per grade, as its summary shows it now or as it stood at the end of the
     *  screen's as-of date; a sheet never used has none and is not started. */
    @GetMapping("/{id}/grade-due-totals")
    public GradeDueTotals gradeDueTotals(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader(value = AsOf.HEADER, required = false) String asOfHeader) {
        String sheet = id.trim();
        Map<String, String> totals = new TreeMap<>();
        if (!service.exists(sheet)) {
            return new GradeDueTotals(false, totals);
        }
        rollups.breakdown(sheet, AsOf.parse(asOfHeader)).gradeDueTotals()
                .forEach((grade, total) -> totals.put(grade, total.toPlainString()));
        return new GradeDueTotals(true, totals);
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
                        rollups.snapshot(sheet, by, request.today());
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

    /** Clone the sheet to another id never used: only a steward may. */
    @PostMapping("/{id}/clone")
    public CloneView cloneTo(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody CloneRequest request) {
        String to = request.to() == null ? "" : request.to().trim();
        clones.cloneTo(id.trim(), SignedInUser.decode(user), to);
        return new CloneView(to);
    }

    /** A date that is not a real yyyy-mm-dd date is refused. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
