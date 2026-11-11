package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;

/**
 * Settles a selection of claims together, in list order: the people in the order they were added, each person's
 * claims newest first. Each claim is settled on its own, as its Settle button would, so a clerk's claim above the
 * clerk limit goes to PENDING and counts as settled; one that cannot be settled (not OPEN, opened or last edited by the same user,
 * dated in a closed period, above the proof threshold without a proof, or gone) is left as it is and counted as
 * refused.
 */
@Service
public class BulkSettleService {

    /** How many of the selected claims were settled and how many refused, and the sheet's last batch number after (null while none). */
    public record Result(int settled, int refused, Long batch) {
    }

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService service;
    private final RoleService roles;
    private final SheetService sheets;

    public BulkSettleService(ClaimRepository claims, PersonRepository people, ClaimService service,
            RoleService roles, SheetService sheets) {
        this.claims = claims;
        this.people = people;
        this.service = service;
        this.roles = roles;
        this.sheets = sheets;
    }

    /** Not transactional: each claim settles in its own change, so a refused one undoes none of the others. */
    public Result settle(String sheetId, String user, Collection<Long> ids) {
        Set<Long> selected = new HashSet<>(ids);
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: every selected claim is refused.
            return new Result(0, selected.size(), lastBatch(sheetId));
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
        int settled = 0;
        // The batch takes its number with the first claim it settles, so a bulk settle that settles none is no batch.
        long[] batch = {0};
        LongSupplier number = () -> batch[0] == 0 ? batch[0] = sheets.nextSettleBatch(sheetId) : batch[0];
        for (Claim claim : ordered) {
            try {
                service.settleInBatch(sheetId, user, claim.getId(), number);
                settled++;
            } catch (ClaimStatusException | ClaimActionForbiddenException | ClaimDeferException | ClaimSettledByOpenerException | ClaimProofException
                    | PeriodClosedException
                    | ClaimNotFoundException | PersonNotFoundException refused) {
                // Left as it is; had it taken the batch's number, that rolled back with it.
                if (settled == 0) {
                    batch[0] = 0;
                }
            }
        }
        // A selected id not on the sheet is refused too.
        return new Result(settled, selected.size() - settled, lastBatch(sheetId));
    }

    private Long lastBatch(String sheetId) {
        long last = sheets.lastSettleBatch(sheetId);
        return last == 0 ? null : last;
    }
}
