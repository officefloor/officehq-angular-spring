package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.setting.PermissionRefusedException;
import net.officefloor.hq.app.setting.SettingService;
import java.util.Set;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;

/** Voids a selection of claims together. */
@Service
public class BulkVoidService {

    /** What a bulk void did: the claims it voided, in the order it voided them, and how many of the
     *  selected claims it refused. */
    public record Result(List<Claim> voided, int refused) {
    }

    private final ClaimListOrder order;
    private final ClaimService service;
    private final RollupService rollups;
    private final SettingService settings;
    private final SheetService sheets;

    public BulkVoidService(ClaimListOrder order, ClaimService service, RollupService rollups, SettingService settings,
            SheetService sheets) {
        this.order = order;
        this.service = service;
        this.rollups = rollups;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Void each selected claim that can be voided, in list order (people in the order they were
     *  added, each person's claims newest first), by exactly the rules of voiding one, with the reason
     *  given; each that cannot, or is not on the sheet, is refused and left as it is. Each claim is
     *  voided on its own, so a refused one writes nothing. */
    public Result voidAll(String sheetId, String user, Set<Long> ids, String reason) {
        List<Claim> voided = new ArrayList<>();
        if (settings.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: every selected claim is refused.
            return new Result(voided, ids.size());
        }
        for (Claim claim : order.selected(sheetId, ids)) {
            try {
                voided.add(rollups.track(sheetId, user, () -> service.changeStatus(sheetId, user,
                        claim.getPersonId(), claim.getId(), ClaimStatus.VOID, reason)));
            } catch (VoidRefusedException | StatusChangeRefusedException | PermissionRefusedException
                    | PeriodClosedException | PersonRetiredException | ClaimLockedException
                    | NoSuchElementException refused) {
                // Left as it is: its change rolled back with its own transaction.
            }
        }
        return new Result(voided, ids.size() - voided.size());
    }
}
