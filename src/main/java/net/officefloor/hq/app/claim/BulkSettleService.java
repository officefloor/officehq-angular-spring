package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Settles a selection of claims together, all or nothing, in list order: the people in the order they were added, each
 * person's claims newest first. Each claim is settled as its Settle button would, so a clerk's claim above the clerk
 * limit goes to PENDING and counts as settled; if any one cannot be settled (not OPEN, opened or last edited by the same
 * user, dated in a closed period, above the proof threshold without a proof, or gone), none is settled and every one
 * counts as refused. A batch that goes through ends with one BATCH_SETTLED record.
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
    private final Audit audit;

    public BulkSettleService(ClaimRepository claims, PersonRepository people, ClaimService service,
            RoleService roles, SheetService sheets, Audit audit) {
        this.audit = audit;
        this.claims = claims;
        this.people = people;
        this.service = service;
        this.roles = roles;
        this.sheets = sheets;
    }

    /** One change: a refused claim rolls back every claim settled before it, the batch's number and their records. */
    @Transactional
    public Result settle(String sheetId, String user, Collection<Long> ids) {
        Set<Long> selected = new HashSet<>(ids);
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: every selected claim is refused.
            return new Result(0, selected.size(), lastBatch(sheetId));
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
        // A selected id not on the sheet cannot be settled, so the whole batch is refused.
        if (ordered.size() != selected.size()) {
            return new Result(0, selected.size(), lastBatch(sheetId));
        }
        // The batch takes its number with the first claim it settles, so a bulk settle that settles none is no batch.
        long[] batch = {0};
        LongSupplier number = () -> batch[0] == 0 ? batch[0] = sheets.nextSettleBatch(sheetId) : batch[0];
        BigDecimal total = BigDecimal.ZERO;
        for (Claim claim : ordered) {
            Claim saved;
            try {
                saved = service.settleInBatch(sheetId, user, claim.getId(), number);
            } catch (ClaimStatusException | ClaimActionForbiddenException | ClaimDeferException | ClaimSettledByOpenerException | ClaimProofException
                    | PeriodClosedException
                    | ClaimNotFoundException | PersonNotFoundException refused) {
                // Nothing of the batch stays: its claims, its number and their records roll back with it.
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new Result(0, selected.size(), lastBatchBefore(sheetId, batch[0]));
            }
            if (saved.getStatus() == ClaimStatus.SETTLED && saved.getSettledDue() != null) {
                total = total.add(saved.getSettledDue());
            }
        }
        if (batch[0] != 0) {
            audit.recordOnCommit("BATCH_SETTLED sheet=" + sheetId + " by=" + user + " batch=" + batch[0]
                    + " count=" + ordered.size() + " total=" + total.setScale(2, RoundingMode.HALF_EVEN).toPlainString());
        }
        return new Result(ordered.size(), 0, lastBatch(sheetId));
    }

    /** The sheet's last batch number as it stands once the batch that took {@code taken} (0 for none) is rolled back. */
    private Long lastBatchBefore(String sheetId, long taken) {
        if (taken == 0) {
            return lastBatch(sheetId);
        }
        return taken == 1 ? null : taken - 1;
    }

    private Long lastBatch(String sheetId) {
        long last = sheets.lastSettleBatch(sheetId);
        return last == 0 ? null : last;
    }
}
