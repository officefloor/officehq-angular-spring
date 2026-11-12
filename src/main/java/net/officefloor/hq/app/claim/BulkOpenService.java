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
 * Opens a selection of claims together, in list order: the people in the order they were added, each person's claims
 * newest first. Each claim is opened on its own, as its Open button would; one that
 * cannot be opened (not a DRAFT, dated in a closed period, above the note threshold with an empty note, over its period's cap, or gone) is left as it is and counted as refused.
 */
@Service
public class BulkOpenService {

    /** How many of the selected claims were opened and how many refused. */
    public record Result(int opened, int refused) {
    }

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService service;
    private final RoleService roles;
    private final SheetService sheets;

    public BulkOpenService(ClaimRepository claims, PersonRepository people, ClaimService service,
            RoleService roles, SheetService sheets) {
        this.claims = claims;
        this.people = people;
        this.service = service;
        this.roles = roles;
        this.sheets = sheets;
    }

    /** Not transactional: each claim opens in its own change, so a refused one undoes none of the others. */
    public Result open(String sheetId, String user, Collection<Long> ids) {
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
        int opened = 0;
        for (Claim claim : ordered) {
            try {
                service.changeStatus(sheetId, user, claim.getId(), ClaimStatus.OPEN);
                opened++;
            } catch (ClaimStatusException | ClaimActionForbiddenException | ClaimNoteException | ClaimCapException
                    | PeriodClosedException
                    | ClaimNotFoundException
                    | PersonNotFoundException refused) {
                // Left as it is.
            }
        }
        // A selected id not on the sheet is refused too.
        return new Result(opened, selected.size() - opened);
    }
}
