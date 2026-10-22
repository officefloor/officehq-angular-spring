package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Moving the selected claims together: opening, settling or reassigning them. Each is moved on its own, as its
 * own button would, in list order: the people in the order they were added, each person's claims
 * newest first (the latest dated first, and of those dated alike the last added), so numbers follow
 * that order. A claim that cannot be moved (or is not on the sheet)
 * is refused and left as it is, writing nothing; the others are still moved. On an archived sheet, or
 * for a viewer, every claim is refused.
 */
@Service
public class BulkMoveService {

    /** What a bulk move did: the claims it moved, in the order it moved them, and how many it refused. */
    public record Result(List<Claim> moved, int refused) {
    }

    /** A person's claims newest first: the latest dated first, and of those dated alike the last added. */
    private static final Comparator<Claim> NEWEST_FIRST = Comparator.comparing(Claim::getDate)
            .thenComparing(Claim::getId).reversed();

    private final ClaimService claims;
    private final PersonRepository people;
    private final RoleService roles;
    private final SheetService sheets;

    public BulkMoveService(ClaimService claims, PersonRepository people, RoleService roles, SheetService sheets) {
        this.claims = claims;
        this.people = people;
        this.roles = roles;
        this.sheets = sheets;
    }

    /** Open the selected claims, each as its Open button would. */
    public Result open(String sheetId, String user, Collection<Long> claimIds) {
        return move(sheetId, user, claimIds, ClaimStatus.OPEN);
    }

    /**
     * Settle the selected claims, each as its Settle button would: a claim a clerk sends PENDING
     * counts as settled.
     */
    public Result settle(String sheetId, String user, Collection<Long> claimIds) {
        return move(sheetId, user, claimIds, ClaimStatus.SETTLED);
    }

    /**
     * Reassign the selected claims to a person, each as its Reassign button would; a claim already the
     * person's is reassigned to them again rather than refused.
     */
    public Result reassign(String sheetId, String user, Collection<Long> claimIds, long personId) {
        return each(sheetId, user, claimIds, id -> claims.reassign(sheetId, user, id, personId, true));
    }

    private Result move(String sheetId, String user, Collection<Long> claimIds, ClaimStatus next) {
        return each(sheetId, user, claimIds, id -> claims.move(sheetId, user, id, next));
    }

    private Result each(String sheetId, String user, Collection<Long> claimIds, Function<Long, Claim> action) {
        Set<Long> selected = new HashSet<>(claimIds);
        if (Role.VIEWER.equals(roles.roleOf(sheetId, user)) || sheets.archived(sheetId)) {
            return new Result(List.of(), selected.size());
        }
        List<Long> ordered = new ArrayList<>();
        List<Claim> sheetClaims = claims.list(sheetId);
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            sheetClaims.stream()
                    .filter(claim -> claim.getPersonId().equals(person.getId()) && selected.contains(claim.getId()))
                    .sorted(NEWEST_FIRST)
                    .forEach(claim -> ordered.add(claim.getId()));
        }
        List<Claim> moved = new ArrayList<>();
        for (Long id : ordered) {
            try {
                moved.add(action.apply(id));
            } catch (ResponseStatusException refused) {
                // left as it is: its move wrote nothing
            }
        }
        return new Result(moved, selected.size() - moved.size());
    }
}
