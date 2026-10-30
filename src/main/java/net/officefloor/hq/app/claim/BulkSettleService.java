package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.period.ClosedPeriodRefused;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Settles a selection of claims together, all or nothing, in list order: the people in the order they were
 * added, each person's newest claim first. Each claim is settled by the rules of settling one; if any one of
 * them is refused, none is settled, every one counts as refused and nothing is written. One left PENDING
 * (a clerk over the clerk limit) is not refused and counts as settled. A bulk settle that goes through is the
 * sheet's next settle batch, numbered 1, 2, ..., each claim it settled shows that number, and after the
 * claims' own lines a BATCH_SETTLED line records it.
 */
@Service
public class BulkSettleService {

    public record Result(int settled, int refused) {
    }

    private final ClaimService claims;
    private final SelectedClaims selected;
    private final AccessService access;
    private final TransactionTemplate transaction;

    public BulkSettleService(ClaimService claims, SelectedClaims selected, AccessService access,
            PlatformTransactionManager transactions) {
        this.access = access;
        this.claims = claims;
        this.selected = selected;
        this.transaction = new TransactionTemplate(transactions);
    }

    public Result settle(String sheetId, String user, Set<Long> claimIds) {
        if (access.isArchived(sheetId) || access.isViewer(sheetId, user)) {
            return new Result(0, claimIds.size()); // an archived sheet, or a viewer, changes nothing: every one refused
        }
        List<Claim> inOrder = selected.inListOrder(sheetId, claimIds);
        if (inOrder.size() != claimIds.size()) {
            return new Result(0, claimIds.size()); // a claim not on the sheet cannot be settled: every one refused
        }
        return transaction.execute(status -> {
            List<Long> settled = new ArrayList<>();
            for (Claim c : inOrder) {
                try {
                    claims.move(sheetId, user, c.getId(), ClaimStatus.SETTLED);
                    settled.add(c.getId());
                } catch (ResponseStatusException | ClosedPeriodRefused refused) {
                    status.setRollbackOnly(); // one refused: none is settled and nothing is written
                    return new Result(0, claimIds.size());
                }
            }
            if (!settled.isEmpty()) {
                claims.settleBatch(sheetId, user, settled);
            }
            return new Result(settled.size(), claimIds.size() - settled.size());
        });
    }
}
