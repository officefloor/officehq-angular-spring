package net.officefloor.hq.app.claim;

import java.util.Set;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.period.ClosedPeriodRefused;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Moves a selection of claims to another person together, in list order: the people in the order they
 * were added, each person's newest claim first. Each claim is moved on its own by the rules of moving
 * one, so one that cannot be moved is refused and left as it is while the rest go through; one already
 * the person's is moved to them again.
 */
@Service
public class BulkReassignService {

    public record Result(int reassigned, int refused) {
    }

    private final ClaimService claims;
    private final SelectedClaims selected;
    private final AccessService access;

    public BulkReassignService(ClaimService claims, SelectedClaims selected, AccessService access) {
        this.access = access;
        this.claims = claims;
        this.selected = selected;
    }

    public Result reassign(String sheetId, String user, Set<Long> claimIds, long toPersonId) {
        if (access.isArchived(sheetId) || access.isViewer(sheetId, user)) {
            return new Result(0, claimIds.size()); // an archived sheet, or a viewer, changes nothing: every one refused
        }
        int reassigned = 0;
        for (Claim c : selected.inListOrder(sheetId, claimIds)) {
            try {
                claims.reassign(sheetId, user, c.getId(), toPersonId, true);
                reassigned++;
            } catch (ResponseStatusException | ClosedPeriodRefused refused) {
                // refused: left as it is
            }
        }
        return new Result(reassigned, claimIds.size() - reassigned);
    }
}
