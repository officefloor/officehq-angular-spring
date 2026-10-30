package net.officefloor.hq.app.claim;

import java.util.Set;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.period.ClosedPeriodRefused;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Settles a selection of claims together, in list order: the people in the order they were added,
 * each person's newest claim first. Each claim is settled on its own by the rules of settling one, so
 * one that cannot be settled is refused and left as it is while the rest go through; one left PENDING
 * (a clerk over the clerk limit) counts as settled.
 */
@Service
public class BulkSettleService {

    public record Result(int settled, int refused) {
    }

    private final ClaimService claims;
    private final SelectedClaims selected;
    private final AccessService access;

    public BulkSettleService(ClaimService claims, SelectedClaims selected, AccessService access) {
        this.access = access;
        this.claims = claims;
        this.selected = selected;
    }

    public Result settle(String sheetId, String user, Set<Long> claimIds) {
        if (access.isArchived(sheetId) || access.isViewer(sheetId, user)) {
            return new Result(0, claimIds.size()); // an archived sheet, or a viewer, changes nothing: every one refused
        }
        int settled = 0;
        for (Claim c : selected.inListOrder(sheetId, claimIds)) {
            try {
                claims.move(sheetId, user, c.getId(), ClaimStatus.SETTLED);
                settled++;
            } catch (ResponseStatusException | ClosedPeriodRefused refused) {
                // refused: left as it is
            }
        }
        return new Result(settled, claimIds.size() - settled);
    }
}
