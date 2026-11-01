package net.officefloor.hq.app.claim;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;

/**
 * Changes to many selected claims at once. Each claim is changed in its own transaction, in list
 * order (people in the order they were added, each person's claims newest first), exactly as if
 * its own button were pressed; one that is refused is left as it is and counted as refused. A
 * viewer's bulk change, or one on an archived sheet, goes through with nothing done: every selected
 * claim is refused.
 */
@Service
public class ClaimBulkService {

    /** How many of the selected claims went through, and how many were refused. */
    public record Result(int done, int refused) {}

    private final ClaimService claims;
    private final ClaimRepository repository;
    private final SettingService settings;
    private final SheetService sheets;

    public ClaimBulkService(ClaimService claims, ClaimRepository repository, SettingService settings, SheetService sheets) {
        this.claims = claims;
        this.repository = repository;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Open each selected claim that can be opened, numbering them in list order. */
    public Result open(String sheetId, String user, Set<Long> ids) {
        return change(sheetId, user, ids, id -> claims.changeStatus(sheetId, user, id, ClaimStatus.OPEN));
    }

    /**
     * Settle each selected claim that can be settled, by the rules of settling one: a claim over
     * the user's clerk limit goes to PENDING and counts as settled. One that settles any is a batch:
     * the sheet's next batch number, given to each claim it settled.
     */
    public Result settle(String sheetId, String user, Set<Long> ids) {
        Integer[] batch = {null};
        return change(sheetId, user, ids,
                id -> batch[0] = claims.settleInBatch(sheetId, user, id, batch[0]).getSettleBatch());
    }

    /** The number of the sheet's last settle batch, or null while there has been none. */
    public Integer lastSettleBatch(String sheetId) {
        int last = sheets.lastSettleBatch(sheetId);
        return last == 0 ? null : last;
    }

    /**
     * Move each selected claim that can be moved to the person, by the rules of moving one (who
     * may, which statuses, closed periods, retirement), with its own CLAIM_REASSIGNED and rollup lines.
     */
    public Result reassign(String sheetId, String user, Set<Long> ids, Long toPersonId) {
        if (refusesAll(sheetId, user)) return new Result(0, ids.size());
        int done = 0;
        for (Claim claim : selected(sheetId, ids)) {
            try {
                claims.reassign(sheetId, user, claim.getId(), toPersonId, true);
                done++;
            } catch (ClaimStatusRefusedException | ClaimNotPermittedException | ClaimReassignRefusedException
                    | PeriodClosedException | PersonRetiredException
                    | ClaimNotFoundException | PersonNotFoundException refused) {
                // left as it is
            }
        }
        return new Result(done, ids.size() - done);
    }

    private boolean refusesAll(String sheetId, String user) {
        return settings.roleOf(sheetId, user) == Role.VIEWER || sheets.isArchived(sheetId);
    }

    /** The selected claims in list order: people in the order they were added, each person's newest first. */
    private List<Claim> selected(String sheetId, Set<Long> ids) {
        return repository.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> ids.contains(c.getId()))
                .sorted(Comparator.comparing(Claim::getPersonId).thenComparing(Claim::getListed, Comparator.reverseOrder()))
                .toList();
    }

    private Result change(String sheetId, String user, Set<Long> ids, Consumer<Long> change) {
        if (refusesAll(sheetId, user)) return new Result(0, ids.size());
        int done = 0;
        for (Claim claim : selected(sheetId, ids)) {
            try {
                change.accept(claim.getId());
                done++;
            } catch (ClaimStatusRefusedException | ClaimSettledByOpenerException | ClaimDeferredException | ClaimProofRequiredException
                    | ClaimNoteRequiredException
                    | ClaimNotPermittedException
                    | PeriodClosedException
                    | ClaimNotFoundException | PersonNotFoundException refused) {
                // left as it is
            }
        }
        return new Result(done, ids.size() - done);
    }
}
