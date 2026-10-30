package net.officefloor.hq.app.claim;

import java.time.LocalDate;
import java.util.Set;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.period.ClosedPeriodRefused;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Re-dates a selection of claims together, in list order: the people in the order they were added, each
 * person's newest claim first. Each claim is re-dated on its own by the rules of re-dating one, so one that
 * cannot be re-dated is refused and left as it is while the rest go through.
 */
@Service
public class BulkRedateService {

    public record Result(int redated, int refused) {
    }

    private final ClaimService claims;
    private final SelectedClaims selected;
    private final AccessService access;

    public BulkRedateService(ClaimService claims, SelectedClaims selected, AccessService access) {
        this.access = access;
        this.claims = claims;
        this.selected = selected;
    }

    public Result redate(String sheetId, String user, Set<Long> claimIds, LocalDate date) {
        if (access.isArchived(sheetId) || access.isViewer(sheetId, user)) {
            return new Result(0, claimIds.size()); // an archived sheet, or a viewer, changes nothing: every one refused
        }
        int redated = 0;
        for (Claim c : selected.inListOrder(sheetId, claimIds)) {
            try {
                claims.redate(sheetId, user, c.getId(), date);
                redated++;
            } catch (ResponseStatusException | ClosedPeriodRefused refused) {
                // refused: left as it is
            }
        }
        return new Result(redated, claimIds.size() - redated);
    }
}
