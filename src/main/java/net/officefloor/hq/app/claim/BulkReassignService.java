package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;

/** Moves a selection of claims to another person together. */
@Service
public class BulkReassignService {

    /** What a bulk move did: the claims it moved, in the order it moved them, and how many of the
     *  selected claims it refused. */
    public record Result(List<Claim> reassigned, int refused) {
    }

    private final ClaimListOrder order;
    private final ClaimService service;
    private final RollupService rollups;
    private final SettingService settings;
    private final SheetService sheets;

    public BulkReassignService(ClaimListOrder order, ClaimService service, RollupService rollups,
            SettingService settings, SheetService sheets) {
        this.order = order;
        this.service = service;
        this.rollups = rollups;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Move each selected claim that can be moved to the person, in list order (people in the order
     *  they were added, each person's claims newest first), by exactly the rules of moving one. Each
     *  that cannot, or is not on the sheet, is refused and left as it is. Each claim is moved on its
     *  own, so a refused one writes nothing. */
    public Result reassign(String sheetId, String user, Set<Long> ids, Long toPersonId) {
        List<Claim> reassigned = new ArrayList<>();
        if (settings.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: every selected claim is refused.
            return new Result(reassigned, ids.size());
        }
        for (Claim claim : order.selected(sheetId, ids)) {
            try {
                reassigned.add(rollups.track(sheetId, user, () -> service.reassignSelected(sheetId, user,
                        claim.getPersonId(), claim.getId(), toPersonId)));
            } catch (ReassignRefusedException | MoveRefusedException | PeriodClosedException | PersonRetiredException
                    | NoSuchElementException refused) {
                // Left as it is: its change rolled back with its own transaction.
            }
        }
        return new Result(reassigned, ids.size() - reassigned.size());
    }
}
