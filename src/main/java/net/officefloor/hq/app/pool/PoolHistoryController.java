package net.officefloor.hq.app.pool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.asof.AsOfService;
import net.officefloor.hq.app.crew.CrewController.CrewView;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonController.HoldView;
import net.officefloor.hq.app.pool.PoolController.PoolView;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pools as they stood for each period: for a closed or soft-closed period, as they stood when it closed (with the
 * sheet's today then as the day), whatever its dates; for any other period whose to-date is on or before the sheet's
 * today, at the end of it: the sheet as of that day (see {@link AsOfService#storeAsOf}), with the day as its today.
 * What the screen works out a member's cut from is given as it then stood: the pools, the crews, the people (their
 * retirement and holds) and the pot.
 */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/pools/history")
public class PoolHistoryController {

    /** A person as the pools then saw them. */
    public record RosterView(Long id, String name, LocalDate retiredFrom, List<HoldView> holds) {
    }

    /**
     * The pools as they stood for the period, with the day standing in for the sheet's today; fixed: as they stood when
     * the period closed, which its cuts keep until it is reopened.
     */
    public record PeriodPools(String period, LocalDate day, List<PoolView> pools, List<CrewView> crews,
            List<RosterView> people, String pot, boolean fixed) {
    }

    private final AsOfService asOf;
    private final SheetService sheets;
    private final PeriodRepository periods;
    private final PoolStanding standing;

    public PoolHistoryController(AsOfService asOf, SheetService sheets, PeriodRepository periods,
            PoolStanding standing) {
        this.asOf = asOf;
        this.sheets = sheets;
        this.periods = periods;
        this.standing = standing;
    }

    /**
     * For each closed or soft-closed period, and each other whose to-date is on or before the sheet's today, by
     * from-date, the pools as they then stood.
     */
    @GetMapping
    public List<PeriodPools> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        LocalDate today = sheets.load(sheetId).getToday();
        List<Period> shown = periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> p.getFixedPools() != null || !p.getTo().isAfter(today)).toList();
        return shown.stream()
                .map(p -> p.getFixedPools() != null ? standing.fixed(p.getFixedPools(), p.getName())
                        : asOf.readAsOf(sheetId, p.getTo(), () -> standing.now(sheetId, p.getName(), p.getTo(), false)))
                .toList();
    }
}
