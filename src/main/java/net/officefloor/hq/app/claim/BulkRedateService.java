package net.officefloor.hq.app.claim;

import java.time.LocalDate;
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

/** Re-dates a selection of claims together. */
@Service
public class BulkRedateService {

    /** What a bulk re-date did: the claims it re-dated, in the order it re-dated them, and how many of
     *  the selected claims it refused. */
    public record Result(List<Claim> redated, int refused) {
    }

    private final ClaimListOrder order;
    private final ClaimService service;
    private final RollupService rollups;
    private final SettingService settings;
    private final SheetService sheets;

    public BulkRedateService(ClaimListOrder order, ClaimService service, RollupService rollups,
            SettingService settings, SheetService sheets) {
        this.order = order;
        this.service = service;
        this.rollups = rollups;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Re-date each selected claim that can be re-dated to the date, in list order (people in the
     *  order they were added, each person's claims newest first), by exactly the rules of re-dating
     *  one. Each that cannot, or is not on the sheet, is refused and left as it is. Each claim is
     *  re-dated on its own, so a refused one writes nothing. */
    public Result redate(String sheetId, String user, Set<Long> ids, LocalDate date) {
        List<Claim> redated = new ArrayList<>();
        if (settings.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: every selected claim is refused.
            return new Result(redated, ids.size());
        }
        for (Claim claim : order.selected(sheetId, ids)) {
            try {
                redated.add(rollups.track(sheetId, user, () -> service.redate(sheetId, user,
                        claim.getPersonId(), claim.getId(), date)));
            } catch (RedateForbiddenException | RedateRefusedException | PeriodClosedException
                    | PersonRetiredException | NoSuchElementException refused) {
                // Left as it is: its change rolled back with its own transaction.
            }
        }
        return new Result(redated, ids.size() - redated.size());
    }
}
