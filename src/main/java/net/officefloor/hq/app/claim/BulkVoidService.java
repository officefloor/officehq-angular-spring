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
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;

/**
 * Voids a selection of claims together, with one reason, in list order: the people in the order they were added, each
 * person's claims newest first. Each claim is voided on its own, as its Void button would, with its own CLAIM_VOIDED
 * and rollup lines; one that cannot be voided (not voidable by the user or in its status, due in a closed period,
 * without a reason from the sheet's void reasons while it has some, or gone) is left as it is and counted as refused.
 */
@Service
public class BulkVoidService {

    /** How many of the selected claims were voided and how many refused. */
    public record Result(int voided, int refused) {
    }

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService service;
    private final RoleService roles;
    private final SheetService sheets;

    public BulkVoidService(ClaimRepository claims, PersonRepository people, ClaimService service,
            RoleService roles, SheetService sheets) {
        this.claims = claims;
        this.people = people;
        this.service = service;
        this.roles = roles;
        this.sheets = sheets;
    }

    /** Not transactional: each claim is voided in its own change, so a refused one undoes none of the others. */
    public Result voidAll(String sheetId, String user, Collection<Long> ids, String reason) {
        Set<Long> selected = new HashSet<>(ids);
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: every selected claim is refused.
            return new Result(0, selected.size());
        }
        Map<Long, Integer> personOrder = new HashMap<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            personOrder.put(person.getId(), personOrder.size());
        }
        List<Claim> ordered = new ArrayList<>(claims.findBySheetIdOrderByListedDescIdDesc(sheetId).stream()
                .filter(c -> selected.contains(c.getId()))
                .toList());
        // Stable: within a person the claims stay newest first.
        ordered.sort((a, b) -> Integer.compare(personOrder.getOrDefault(a.getPersonId(), Integer.MAX_VALUE),
                personOrder.getOrDefault(b.getPersonId(), Integer.MAX_VALUE)));
        int voided = 0;
        for (Claim claim : ordered) {
            try {
                service.changeStatus(sheetId, user, claim.getId(), ClaimStatus.VOID, reason);
                voided++;
            } catch (ClaimStatusException | ClaimActionForbiddenException | ClaimVoidException
                    | ClaimVoidReasonException | PeriodClosedException | ClaimNotFoundException
                    | PersonNotFoundException refused) {
                // Left as it is.
            }
        }
        // A selected id not on the sheet is refused too.
        return new Result(voided, selected.size() - voided);
    }
}
