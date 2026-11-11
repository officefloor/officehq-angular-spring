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
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;

/**
 * Moves a selection of claims to one person together, in list order: the people in the order they were added, each
 * person's claims newest first. Each claim is moved on its own, as its Reassign button would, with its own
 * CLAIM_REASSIGNED and rollup lines; one that cannot be moved (a role that may not reassign, SETTLED or VOID, due in a
 * closed period, the person retired before its date, or gone) is left as it is and counted as refused.
 */
@Service
public class BulkReassignService {

    /** How many of the selected claims were moved and how many refused. */
    public record Result(int reassigned, int refused) {
    }

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService service;
    private final RoleService roles;
    private final SheetService sheets;

    public BulkReassignService(ClaimRepository claims, PersonRepository people, ClaimService service,
            RoleService roles, SheetService sheets) {
        this.claims = claims;
        this.people = people;
        this.service = service;
        this.roles = roles;
        this.sheets = sheets;
    }

    /** Not transactional: each claim moves in its own change, so a refused one undoes none of the others. */
    public Result reassign(String sheetId, String user, Collection<Long> ids, Long toPersonId) {
        Set<Long> selected = new HashSet<>(ids);
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: every selected claim is refused.
            return new Result(0, selected.size());
        }
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
        int reassigned = 0;
        for (Claim claim : ordered) {
            try {
                service.reassign(sheetId, user, claim.getId(), toPersonId, true);
                reassigned++;
            } catch (ClaimReassignException | ClaimReassignForbiddenException | PeriodClosedException
                    | PersonRetiredException | ClaimNotFoundException | PersonNotFoundException refused) {
                // Left as it is.
            }
        }
        // A selected id not on the sheet is refused too.
        return new Result(reassigned, selected.size() - reassigned);
    }
}
