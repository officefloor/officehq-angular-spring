package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.setting.PermissionRefusedException;
import net.officefloor.hq.app.setting.SettingService;
import java.util.Set;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;

/** Settles a selection of claims together. */
@Service
public class BulkSettleService {

    /** What a bulk settle did: the claims it settled (SETTLED, or PENDING a steward's approval), in
     *  the order it settled them, and how many of the selected claims it refused. */
    public record Result(List<Claim> settled, int refused) {
    }

    private final ClaimListOrder order;
    private final ClaimService service;
    private final RollupService rollups;
    private final SettingService settings;
    private final SheetService sheets;

    public BulkSettleService(ClaimListOrder order, ClaimService service, RollupService rollups, SettingService settings,
            SheetService sheets) {
        this.order = order;
        this.service = service;
        this.rollups = rollups;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Settle each selected claim that can be settled, in list order (people in the order they were
     *  added, each person's claims newest first), by exactly the rules of settling one: a clerk's
     *  claim above the clerk limit goes PENDING and counts as settled. Each that cannot, or is not
     *  on the sheet, is refused and left as it is. Each claim is settled on its own, so a refused
     *  one writes nothing. */
    public Result settle(String sheetId, String user, Set<Long> ids) {
        List<Claim> settled = new ArrayList<>();
        if (settings.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: every selected claim is refused.
            return new Result(settled, ids.size());
        }
        for (Claim claim : order.selected(sheetId, ids)) {
            try {
                settled.add(rollups.track(sheetId, user, () -> service.changeStatus(sheetId, user,
                        claim.getPersonId(), claim.getId(), ClaimStatus.SETTLED)));
            } catch (StatusChangeRefusedException | PermissionRefusedException | OwnClaimSettleRefusedException | ProofRequiredException | PeriodClosedException
                    | NoSuchElementException refused) {
                // Left as it is: its change rolled back with its own transaction.
            }
        }
        return new Result(settled, ids.size() - settled.size());
    }
}
