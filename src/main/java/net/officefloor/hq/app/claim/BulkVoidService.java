package net.officefloor.hq.app.claim;

import java.util.Set;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.period.ClosedPeriodRefused;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Voids a selection of claims together, in list order: the people in the order they were added, each
 * person's newest claim first. Each claim is voided on its own by the rules of voiding one (with the given
 * reason), so one that cannot be voided is refused and left as it is while the rest go through.
 */
@Service
public class BulkVoidService {

    public record Result(int voided, int refused) {
    }

    private final ClaimService claims;
    private final SelectedClaims selected;
    private final AccessService access;

    public BulkVoidService(ClaimService claims, SelectedClaims selected, AccessService access) {
        this.access = access;
        this.claims = claims;
        this.selected = selected;
    }

    public Result voidAll(String sheetId, String user, Set<Long> claimIds, String reason) {
        if (access.isArchived(sheetId) || access.isViewer(sheetId, user)) {
            return new Result(0, claimIds.size()); // an archived sheet, or a viewer, changes nothing: every one refused
        }
        int voided = 0;
        for (Claim c : selected.inListOrder(sheetId, claimIds)) {
            try {
                claims.move(sheetId, user, c.getId(), ClaimStatus.VOID, reason);
                voided++;
            } catch (ResponseStatusException | ClosedPeriodRefused refused) {
                // refused: left as it is
            }
        }
        return new Result(voided, claimIds.size() - voided);
    }
}
