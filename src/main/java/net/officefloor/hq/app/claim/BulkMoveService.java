package net.officefloor.hq.app.claim;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Moving the selected claims together: opening, settling, reassigning, re-dating or voiding them. Each is moved on its own, as its
 * own button would, in list order: the people by their position, each person's claims
 * newest first as they are listed, so numbers follow that order. A claim that cannot be moved (or is not on the sheet)
 * is refused and left as it is, writing nothing; the others are still moved (except in a bulk settle,
 * which is all or nothing). On an archived sheet, or
 * for a viewer, every claim is refused.
 */
@Service
public class BulkMoveService {

    /**
     * What a bulk move did: the claims it moved, in the order it moved them, how many it refused, and
     * the number of the settle batch it was (null unless it is a bulk settle that settled a claim).
     */
    public record Result(List<Claim> moved, int refused, Integer batch) {

        Result(List<Claim> moved, int refused) {
            this(moved, refused, null);
        }
    }

    private final ClaimService claims;
    private final PersonRepository people;
    private final RoleService roles;
    private final SheetService sheets;
    private final TransactionTemplate transactions;

    public BulkMoveService(ClaimService claims, PersonRepository people, RoleService roles, SheetService sheets,
            PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
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
     * Settle the selected claims, each as its Settle button would, all or nothing: a claim a clerk
     * sends PENDING counts as settled, but when any one is refused, none is settled, every one counts
     * as refused and nothing is written. When it settles them, it is the sheet's next settle batch,
     * each claim carries that batch's number, and BATCH_SETTLED follows the claims' own records.
     */
    public Result settle(String sheetId, String user, Collection<Long> claimIds) {
        Set<Long> selected = new HashSet<>(claimIds);
        return transactions.execute(status -> {
            Result settled = move(sheetId, user, selected, ClaimStatus.SETTLED);
            if (settled.refused() > 0) {
                status.setRollbackOnly();
                return new Result(List.of(), selected.size());
            }
            if (settled.moved().isEmpty()) {
                return settled;
            }
            ClaimService.Batch batch = claims.batch(sheetId, user,
                    settled.moved().stream().map(Claim::getId).toList());
            return new Result(batch.claims(), 0, batch.number());
        });
    }

    /**
     * Reassign the selected claims to a person, each as its Reassign button would, in list order, so
     * their new numbers follow that order; a claim already the person's is reassigned to them again
     * rather than refused.
     */
    public Result reassign(String sheetId, String user, Collection<Long> claimIds, long personId) {
        return each(sheetId, user, claimIds, id -> claims.reassign(sheetId, user, id, personId, true));
    }

    /**
     * Re-date the selected claims to a date, each as its row's re-date would, in list order (the order
     * taken before any is re-dated).
     */
    public Result redate(String sheetId, String user, Collection<Long> claimIds, LocalDate date) {
        return each(sheetId, user, claimIds, id -> claims.redate(sheetId, user, id, date));
    }

    /**
     * Void the selected claims, each as its row's void would (with its CLAIM_VOIDED and rollup records),
     * in list order, all with the one reason chosen (null or blank for none).
     */
    public Result voidAll(String sheetId, String user, Collection<Long> claimIds, String reason) {
        return each(sheetId, user, claimIds, id -> claims.move(sheetId, user, id, ClaimStatus.VOID, reason));
    }

    private Result move(String sheetId, String user, Collection<Long> claimIds, ClaimStatus next) {
        return each(sheetId, user, claimIds, id -> claims.move(sheetId, user, id, next));
    }

    private Result each(String sheetId, String user, Collection<Long> claimIds, Function<Long, Claim> action) {
        Set<Long> selected = new HashSet<>(claimIds);
        return each(sheetId, user, selected, listOrder(sheetId, selected), action);
    }

    /** The selected claims in list order: each person's as the sheet lists them, newest first. */
    private List<Long> listOrder(String sheetId, Set<Long> selected) {
        List<Long> ordered = new ArrayList<>();
        List<Claim> sheetClaims = claims.list(sheetId);
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            sheetClaims.stream()
                    .filter(claim -> claim.getPersonId().equals(person.getId()) && selected.contains(claim.getId()))
                    .forEach(claim -> ordered.add(claim.getId()));
        }
        return ordered;
    }

    private Result each(String sheetId, String user, Set<Long> selected, List<Long> ordered,
            Function<Long, Claim> action) {
        if (Role.VIEWER.equals(roles.roleOf(sheetId, user)) || sheets.archived(sheetId)) {
            return new Result(List.of(), selected.size());
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
