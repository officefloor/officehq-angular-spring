package net.officefloor.hq.app.pool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.asof.AsOfService;
import net.officefloor.hq.app.crew.CrewController;
import net.officefloor.hq.app.crew.CrewController.CrewView;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonController.HoldView;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolController.PoolView;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pools as they stood at the end of each period whose to-date is on or before the sheet's today: the sheet as of
 * that day (see {@link AsOfService#storeAsOf}), with the day as its today. What the screen works out a member's cut
 * from is given as it then stood: the pools, the crews, the people (their retirement and holds) and the pot.
 */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/pools/history")
public class PoolHistoryController {

    /** A person as the pools then saw them. */
    public record RosterView(Long id, String name, LocalDate retiredFrom, List<HoldView> holds) {
    }

    /** The pools as they stood at the end of the period, with the day standing in for the sheet's today. */
    public record PeriodPools(String period, LocalDate day, List<PoolView> pools, List<CrewView> crews,
            List<RosterView> people, String pot) {
    }

    private final AsOfService asOf;
    private final SheetService sheets;
    private final PeriodRepository periods;
    private final PersonRepository people;
    private final PoolController pools;
    private final CrewController crews;

    public PoolHistoryController(AsOfService asOf, SheetService sheets, PeriodRepository periods,
            PersonRepository people, PoolController pools, CrewController crews) {
        this.asOf = asOf;
        this.sheets = sheets;
        this.periods = periods;
        this.people = people;
        this.pools = pools;
        this.crews = crews;
    }

    /** For each period whose to-date is on or before the sheet's today, by from-date, the pools as they then stood. */
    @GetMapping
    public List<PeriodPools> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        LocalDate today = sheets.load(sheetId).getToday();
        List<Period> ended = periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> !p.getTo().isAfter(today)).toList();
        return ended.stream().map(p -> asOf.readAsOf(sheetId, p.getTo(), () -> then(sheetId, p))).toList();
    }

    private PeriodPools then(String sheetId, Period period) {
        Sheet sheet = sheets.load(sheetId);
        List<RosterView> roster = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .map(p -> new RosterView(p.getId(), p.getName(), p.getRetiredFrom(),
                        p.getHolds().stream().map(HoldView::of).toList()))
                .toList();
        return new PeriodPools(period.getName(), period.getTo(), pools.list(sheetId), crews.list(sheetId), roster,
                sheet.getPot() == null ? null : sheet.getPot().setScale(2).toPlainString());
    }
}
