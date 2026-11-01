package net.officefloor.hq.app.claim;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.PersonNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Changes to many selected claims at once. Each claim is changed in its own transaction, in list
 * order (people in the order they were added, each person's claims newest first), exactly as if
 * its own button were pressed; one that is refused is left as it is and counted as refused.
 */
@Service
public class ClaimBulkService {

    /** How many of the selected claims went through, and how many were refused. */
    public record Result(int done, int refused) {}

    private final ClaimService claims;
    private final ClaimRepository repository;

    public ClaimBulkService(ClaimService claims, ClaimRepository repository) {
        this.claims = claims;
        this.repository = repository;
    }

    /** Open each selected claim that can be opened, numbering them in list order. */
    public Result open(String sheetId, String user, Set<Long> ids) {
        return change(sheetId, user, ids, ClaimStatus.OPEN);
    }

    /**
     * Settle each selected claim that can be settled, by the rules of settling one: a claim over
     * the user's clerk limit goes to PENDING and counts as settled.
     */
    public Result settle(String sheetId, String user, Set<Long> ids) {
        return change(sheetId, user, ids, ClaimStatus.SETTLED);
    }

    private Result change(String sheetId, String user, Set<Long> ids, ClaimStatus next) {
        List<Claim> selected = repository.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> ids.contains(c.getId()))
                .sorted(Comparator.comparing(Claim::getPersonId).thenComparing(Claim::getListed, Comparator.reverseOrder()))
                .toList();
        int done = 0;
        for (Claim claim : selected) {
            try {
                claims.changeStatus(sheetId, user, claim.getId(), next);
                done++;
            } catch (ClaimStatusRefusedException | ClaimSettledByOpenerException | ClaimDeferredException | ClaimProofRequiredException
                    | ClaimNotPermittedException
                    | PeriodClosedException
                    | ClaimNotFoundException | PersonNotFoundException refused) {
                // left as it is
            }
        }
        return new Result(done, ids.size() - done);
    }
}
