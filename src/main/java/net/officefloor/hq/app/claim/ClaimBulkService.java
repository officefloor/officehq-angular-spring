package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/** Status changes asked of several claims at once. Each claim changes in its own transaction, through
 * {@link ClaimService}, so a refused one changes (and records) nothing while the others go through. A
 * viewer's bulk change, or one on an archived sheet, goes through with nothing done: every claim asked
 * is refused. */
@Service
public class ClaimBulkService {

    /** How a bulk change went: the claims it changed, and how many it refused. */
    public record Outcome(List<Claim> changed, int refused) {
    }

    private final ClaimService service;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SettingsService settings;
    private final SheetRepository sheets;

    public ClaimBulkService(ClaimService service, ClaimRepository claims, PersonRepository people,
            SettingsService settings, SheetRepository sheets) {
        this.sheets = sheets;
        this.service = service;
        this.claims = claims;
        this.people = people;
        this.settings = settings;
    }

    /** Open each of the claims that can be opened, in list order (people in the order they were
     * added, each person's claims newest first), so they are numbered in that order; any that cannot
     * be opened (or is not on the sheet) is left as it is and counted as refused. */
    public Outcome open(String sheetId, String user, Collection<Long> claimIds) {
        return change(sheetId, user, claimIds, ClaimStatus.OPEN);
    }

    /** Settle each of the claims that can be settled, in list order, by exactly the rules of settling
     * one claim (one that goes to PENDING instead counts as settled); any that cannot be settled (or
     * is not on the sheet) is left as it is and counted as refused. One that settles at least one claim is
     * the sheet's next settle batch: each claim it settled carries the batch's number. */
    public Outcome settle(String sheetId, String user, Collection<Long> claimIds) {
        Outcome outcome = change(sheetId, user, claimIds, ClaimStatus.SETTLED);
        if (outcome.changed().isEmpty()) return outcome;
        return new Outcome(service.settleBatch(sheetId, outcome.changed()), outcome.refused());
    }

    /** Move each of the claims that can be moved to the person, in list order, by exactly the rules of
     * moving one claim (each move records its CLAIM_REASSIGNED and rollup lines); any that cannot be
     * moved (or is not on the sheet) is left as it is and counted as refused. */
    public Outcome reassign(String sheetId, String user, Collection<Long> claimIds, long personId) {
        Set<Long> asked = Set.copyOf(claimIds);
        if (refusesAll(sheetId, user)) return new Outcome(List.of(), asked.size());
        List<Claim> found = inListOrder(sheetId, asked);
        int refused = asked.size() - found.size();
        List<Claim> changed = new ArrayList<>();
        for (Claim claim : found) {
            try {
                changed.add(service.reassignOfBulk(sheetId, user, claim.getId(), personId));
            } catch (ClaimReassignException | ClaimPeriodClosedException | ClaimNotFoundException | ClaimNotStewardException
                    | PersonNotFoundException | PersonRetiredException e) {
                refused++;
            }
        }
        return new Outcome(changed, refused);
    }

    private boolean refusesAll(String sheetId, String user) {
        return settings.roleOf(sheetId, user) == Role.VIEWER
                || sheets.findById(sheetId).map(Sheet::isArchived).orElse(false);
    }

    /** The claims asked that are on the sheet: people in the order they were added, each person's
     * claims newest first. */
    private List<Claim> inListOrder(String sheetId, Set<Long> asked) {
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> listed = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < listed.size(); i++) personOrder.put(listed.get(i).getId(), i);
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId).stream()
                .filter(c -> asked.contains(c.getId()))
                .sorted(Comparator.comparing((Claim c) -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE))
                        .thenComparing(Claim::getListed, Comparator.reverseOrder())
                        .thenComparing(Claim::getId, Comparator.reverseOrder()))
                .toList();
    }

    private Outcome change(String sheetId, String user, Collection<Long> claimIds, ClaimStatus next) {
        Set<Long> asked = Set.copyOf(claimIds);
        if (refusesAll(sheetId, user)) return new Outcome(List.of(), asked.size());
        List<Claim> found = inListOrder(sheetId, asked);
        int refused = asked.size() - found.size();
        List<Claim> changed = new ArrayList<>();
        for (Claim claim : found) {
            try {
                changed.add(service.changeStatus(sheetId, user, claim.getId(), next));
            } catch (ClaimStatusChangeException | ClaimPeriodClosedException | ClaimSelfSettleException | ClaimProofRequiredException | ClaimNoteRequiredException | ClaimNotFoundException | ClaimNotStewardException
                    | PersonNotFoundException e) {
                refused++;
            }
        }
        return new Outcome(changed, refused);
    }
}
