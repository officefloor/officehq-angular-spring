package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;

/**
 * Settles a selection of claims together, in list order: the people in the order they were added, each person's
 * claims newest first. Each claim is settled on its own, as its Settle button would, so a clerk's claim above the
 * clerk limit goes to PENDING and counts as settled; one that cannot be settled (not OPEN, opened by the same user,
 * dated in a closed period, or gone) is left as it is and counted as refused.
 */
@Service
public class BulkSettleService {

    /** How many of the selected claims were settled and how many refused. */
    public record Result(int settled, int refused) {
    }

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService service;

    public BulkSettleService(ClaimRepository claims, PersonRepository people, ClaimService service) {
        this.claims = claims;
        this.people = people;
        this.service = service;
    }

    /** Not transactional: each claim settles in its own change, so a refused one undoes none of the others. */
    public Result settle(String sheetId, String user, Collection<Long> ids) {
        Set<Long> selected = new HashSet<>(ids);
        Map<Long, Integer> personOrder = new HashMap<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            personOrder.put(person.getId(), personOrder.size());
        }
        List<Claim> ordered = new ArrayList<>(claims.findBySheetIdOrderByListedDescIdDesc(sheetId).stream()
                .filter(c -> selected.contains(c.getId()))
                .toList());
        // Stable: within a person the claims stay newest first.
        ordered.sort((a, b) -> Integer.compare(personOrder.getOrDefault(a.getPersonId(), Integer.MAX_VALUE),
                personOrder.getOrDefault(b.getPersonId(), Integer.MAX_VALUE)));
        int settled = 0;
        for (Claim claim : ordered) {
            try {
                service.changeStatus(sheetId, user, claim.getId(), ClaimStatus.SETTLED);
                settled++;
            } catch (ClaimStatusException | ClaimDeferException | ClaimSettledByOpenerException | PeriodClosedException
                    | ClaimNotFoundException | PersonNotFoundException refused) {
                // Left as it is.
            }
        }
        // A selected id not on the sheet is refused too.
        return new Result(settled, selected.size() - settled);
    }
}
