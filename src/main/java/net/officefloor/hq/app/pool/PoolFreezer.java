package net.officefloor.hq.app.pool;

import net.officefloor.hq.app.period.PeriodFixing;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Fixes on a period, as it is closed or soft-closed, the pools, pot and people as they stand at that
 *  moment, so each pool member's cut for it shows as it stood then until it is reopened. */
@Component
public class PoolFreezer {

    private final PoolHistoryService pastPools;
    private final JsonMapper json;

    public PoolFreezer(PoolHistoryService pastPools, JsonMapper json) {
        this.pastPools = pastPools;
        this.json = json;
    }

    @EventListener
    public void fixing(PeriodFixing event) {
        PoolHistoryService.PeriodPools now = pastPools.now(event.period().getSheetId(), event.period().getName());
        event.period().freezePools(json.writeValueAsString(PoolController.PeriodPoolsView.of(now)));
    }
}
