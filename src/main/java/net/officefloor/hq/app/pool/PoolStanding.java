package net.officefloor.hq.app.pool;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.crew.CrewController;
import net.officefloor.hq.app.person.PersonController.HoldView;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolHistoryController.PeriodPools;
import net.officefloor.hq.app.pool.PoolHistoryController.RosterView;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pools of a sheet as they stand in the store read now: what the screen works out a member's cut from (the pools,
 * the crews, the people with their retirement and holds, and the pot), for a period with a day standing in for the
 * sheet's today. A closed period keeps them as JSON, so its cuts stay as they were when it closed.
 */
@Service
public class PoolStanding {

    private final SheetService sheets;
    private final PersonRepository people;
    private final PoolController pools;
    private final CrewController crews;
    private final JsonMapper json;

    public PoolStanding(SheetService sheets, PersonRepository people, PoolController pools, CrewController crews,
            JsonMapper json) {
        this.sheets = sheets;
        this.people = people;
        this.pools = pools;
        this.crews = crews;
        this.json = json;
    }

    /** The pools as they stand now, for the period, with the day as its today; fixed: kept from a close. */
    public PeriodPools now(String sheetId, String period, LocalDate day, boolean fixed) {
        Sheet sheet = sheets.load(sheetId);
        List<RosterView> roster = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .map(p -> new RosterView(p.getId(), p.getName(), p.getRetiredFrom(),
                        p.getHolds().stream().map(HoldView::of).toList()))
                .toList();
        return new PeriodPools(period, day, pools.list(sheetId), crews.list(sheetId), roster,
                sheet.getPot() == null ? null : sheet.getPot().setScale(2).toPlainString(), fixed);
    }

    /** The pools as they stand now, with the sheet's today, as kept on a period when it closes. */
    public String fix(String sheetId, String period) {
        return json.writeValueAsString(now(sheetId, period, sheets.load(sheetId).getToday(), true));
    }

    /** Pools kept by {@link #fix}, under the period's name now. */
    public PeriodPools fixed(String kept, String period) {
        PeriodPools p = json.readValue(kept, PeriodPools.class);
        return new PeriodPools(period, p.day(), p.pools(), p.crews(), p.people(), p.pot(), true);
    }
}
