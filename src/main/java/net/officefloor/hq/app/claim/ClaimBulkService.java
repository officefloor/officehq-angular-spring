package net.officefloor.hq.app.claim;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Changes made to many claims at once; each claim is changed in its own transaction, so one that
 *  is refused leaves the others as they are. */
@Service
public class ClaimBulkService {

    /** How many claims a bulk change made, and how many it refused. */
    public record Outcome(int done, int refused) {
    }

    private final ClaimService claims;
    private final ClaimRepository repository;
    private final PersonRepository people;

    public ClaimBulkService(ClaimService claims, ClaimRepository repository, PersonRepository people) {
        this.claims = claims;
        this.repository = repository;
        this.people = people;
    }

    /** Open each of the claims that can be opened, numbering them in list order (people in the
     *  order they were added, newest claim first within a person); each that cannot is refused and
     *  left as it is, and so is an id that names no claim on the sheet. */
    public Outcome open(String sheetId, String user, Set<Long> claimIds) {
        return change(sheetId, user, claimIds, ClaimStatus.OPEN);
    }

    /** Settle each of the claims that can be settled, in list order, exactly as settling one: a claim
     *  a clerk's limit leaves pending counts as settled; each that cannot is refused and left as it
     *  is, and so is an id that names no claim on the sheet. */
    public Outcome settle(String sheetId, String user, Set<Long> claimIds) {
        return change(sheetId, user, claimIds, ClaimStatus.SETTLED);
    }

    private Outcome change(String sheetId, String user, Set<Long> claimIds, ClaimStatus to) {
        int done = 0;
        for (Claim claim : inListOrder(sheetId, claimIds)) {
            try {
                claims.changeStatus(sheetId, user, claim.getId(), to);
                done++;
            } catch (ResponseStatusException refused) {
                // left as it is
            }
        }
        return new Outcome(done, claimIds.size() - done);
    }

    /** The claims of the ids on the sheet in list order: people in the order they were added, newest
     *  claim first within a person. */
    private List<Claim> inListOrder(String sheetId, Set<Long> claimIds) {
        Map<Long, Integer> rank = new HashMap<>();
        List<Person> listed = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < listed.size(); i++) {
            rank.put(listed.get(i).getId(), i);
        }
        return repository.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> claimIds.contains(c.getId()))
                .sorted(Comparator.comparingInt((Claim c) -> rank.getOrDefault(c.getPersonId(), Integer.MAX_VALUE))
                        .thenComparing(Claim::getListed, Comparator.reverseOrder()))
                .toList();
    }
}
