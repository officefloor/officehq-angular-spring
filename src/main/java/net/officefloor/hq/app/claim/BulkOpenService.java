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

/** Opens a selection of claims together. */
@Service
public class BulkOpenService {

    /** What a bulk open did: the claims it opened, in the order it opened them, and how many of the
     *  selected claims it refused. */
    public record Result(List<Claim> opened, int refused) {
    }

    private final ClaimListOrder order;
    private final ClaimService service;
    private final RollupService rollups;
    private final SettingService settings;
    private final SheetService sheets;

    public BulkOpenService(ClaimListOrder order, ClaimService service, RollupService rollups, SettingService settings,
            SheetService sheets) {
        this.order = order;
        this.service = service;
        this.rollups = rollups;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Open each selected claim that can be opened, in list order (people in the order they were
     *  added, each person's claims newest first), so they are numbered in that order; each that
     *  cannot, or is not on the sheet, is refused and left as it is. Each claim is opened on its
     *  own, so a refused one writes nothing. */
    public Result open(String sheetId, String user, Set<Long> ids) {
        List<Claim> opened = new ArrayList<>();
        if (settings.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: every selected claim is refused.
            return new Result(opened, ids.size());
        }
        for (Claim claim : order.selected(sheetId, ids)) {
            try {
                opened.add(rollups.track(sheetId, user, () -> service.changeStatus(sheetId, user,
                        claim.getPersonId(), claim.getId(), ClaimStatus.OPEN)));
            } catch (StatusChangeRefusedException | NoteRequiredException | PeriodCapException | MaxOpenException | PermissionRefusedException | PeriodClosedException | NoSuchElementException refused) {
                // Left as it is: its change rolled back with its own transaction.
            }
        }
        return new Result(opened, ids.size() - opened.size());
    }
}
