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
        List<Claim> selected = repository.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> ids.contains(c.getId()))
                .sorted(Comparator.comparing(Claim::getPersonId).thenComparing(Claim::getId, Comparator.reverseOrder()))
                .toList();
        int done = 0;
        for (Claim claim : selected) {
            try {
                claims.changeStatus(sheetId, user, claim.getId(), ClaimStatus.OPEN);
                done++;
            } catch (ClaimStatusRefusedException | PeriodClosedException | ClaimNotFoundException
                    | PersonNotFoundException refused) {
                // left as it is
            }
        }
        return new Result(done, ids.size() - done);
    }
}
