package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opening the selected claims together. Each is opened on its own, as its Open button would, in list
 * order: the people in the order they were added, each person's claims newest first, so the numbers
 * follow that order. A claim that cannot be opened (or is not on the sheet) is refused and left as it
 * is, writing nothing; the others are still opened.
 */
@Service
public class BulkOpenService {

    /** What a bulk open did: the claims it opened, in the order it opened them, and how many it refused. */
    public record Result(List<Claim> opened, int refused) {
    }

    private final ClaimService claims;
    private final PersonRepository people;

    public BulkOpenService(ClaimService claims, PersonRepository people) {
        this.claims = claims;
        this.people = people;
    }

    public Result open(String sheetId, String user, Collection<Long> claimIds) {
        Set<Long> selected = new HashSet<>(claimIds);
        List<Long> ordered = new ArrayList<>();
        List<Claim> sheetClaims = claims.list(sheetId);
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            for (Claim claim : sheetClaims) {
                if (claim.getPersonId().equals(person.getId()) && selected.contains(claim.getId())) {
                    ordered.add(claim.getId());
                }
            }
        }
        List<Claim> opened = new ArrayList<>();
        for (Long id : ordered) {
            try {
                opened.add(claims.move(sheetId, user, id, ClaimStatus.OPEN));
            } catch (ResponseStatusException refused) {
                // left as it is: its move wrote nothing
            }
        }
        return new Result(opened, selected.size() - opened.size());
    }
}
