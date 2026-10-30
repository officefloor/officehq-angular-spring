package net.officefloor.hq.app.claim;

import java.util.Set;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.period.ClosedPeriodRefused;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opens a selection of claims together, numbered in list order: the people in the order they were
 * added, each person's newest claim first. Each claim is opened on its own, so one that cannot be
 * opened is refused and left as it is while the rest go through.
 */
@Service
public class BulkOpenService {

    public record Result(int opened, int refused) {
    }

    private final ClaimService claims;
    private final SelectedClaims selected;
    private final AccessService access;

    public BulkOpenService(ClaimService claims, SelectedClaims selected, AccessService access) {
        this.access = access;
        this.claims = claims;
        this.selected = selected;
    }

    public Result open(String sheetId, String user, Set<Long> claimIds) {
        if (access.isArchived(sheetId) || access.isViewer(sheetId, user)) {
            return new Result(0, claimIds.size()); // an archived sheet, or a viewer, changes nothing: every one refused
        }
        int opened = 0;
        for (Claim c : selected.inListOrder(sheetId, claimIds)) {
            try {
                claims.move(sheetId, user, c.getId(), ClaimStatus.OPEN);
                opened++;
            } catch (ResponseStatusException | ClosedPeriodRefused refused) {
                // refused: left as it is
            }
        }
        return new Result(opened, claimIds.size() - opened);
    }
}
