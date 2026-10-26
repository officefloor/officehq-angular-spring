package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Status changes made to several claims together: opens and moves each one on its own, a settle all or nothing. */
@Service
public class ClaimBulkService {

    /** The claims a bulk action changed, in the order it took them, and how many it refused. */
    public record Result(List<Claim> changed, int refused) {
    }

    /** The claims a bulk settle settled or made PENDING, how many it refused, and its batch number (null for none). */
    public record SettleResult(List<Claim> settled, int refused, Integer batch) {
    }

    private final ClaimService claims;
    private final ClaimRepository repository;
    private final PersonRepository people;
    private final SettingsService settings;
    private final Audit audit;
    private final TransactionTemplate transactions;

    public ClaimBulkService(ClaimService claims, ClaimRepository repository, PersonRepository people,
            SettingsService settings, Audit audit, PlatformTransactionManager transactionManager) {
        this.claims = claims;
        this.repository = repository;
        this.people = people;
        this.settings = settings;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Opens each of the claims that can be opened, in list order (the order of people, newest claim first within a
     * person), so they are numbered in that order. Each is opened in its own transaction: one that is refused, or is
     * not a claim of the sheet, is left as it is and counted as refused.
     */
    public Result open(String by, String sheetId, Set<Long> ids) {
        return each(by, sheetId, ids, id -> claims.changeStatus(by, sheetId, id, ClaimStatus.OPEN));
    }

    /**
     * Settles the claims all or nothing, by the rules of settling one claim: one the clerk limit makes PENDING counts
     * as settled. When any of them is refused, or is not a claim of the sheet, none is settled, every one counts as
     * refused and nothing is written. Otherwise each is settled in list order in the one transaction, which is the
     * sheet's next batch: each claim it settled keeps the batch's number, and after the claims' own records a
     * BATCH_SETTLED record gives the batch, its count and the frozen figures of those SETTLED added up.
     */
    public SettleResult settle(String by, String sheetId, Set<Long> ids) {
        SettleResult refusedAll = new SettleResult(List.of(), ids.size(), null);
        if (settings.isReadOnly(sheetId, by)) {
            return refusedAll;
        }
        List<Long> ordered = ordered(sheetId, ids);
        if (ordered.isEmpty() || ordered.size() != ids.size()) {
            return refusedAll;
        }
        return transactions.execute(status -> {
            List<Claim> settled = new ArrayList<>();
            for (Long id : ordered) {
                try {
                    settled.add(claims.changeStatus(by, sheetId, id, ClaimStatus.SETTLED));
                } catch (ResponseStatusException e) {
                    // one refused refuses them all: the rollback drops every change and every record
                    status.setRollbackOnly();
                    return refusedAll;
                }
            }
            int batch = claims.numberSettleBatch(sheetId, settled.stream().map(Claim::getId).toList());
            settled.forEach(c -> c.setSettleBatch(batch));
            BigDecimal total = settled.stream()
                    .filter(c -> c.getStatus() == ClaimStatus.SETTLED && c.getSettledDue() != null)
                    .map(Claim::getSettledDue).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            audit.recordOnCommit("BATCH_SETTLED sheet=" + sheetId + " by=" + by + " batch=" + batch + " count="
                    + settled.size() + " total=" + total.toPlainString());
            return new SettleResult(settled, 0, batch);
        });
    }

    /**
     * Moves each of the claims that can be moved to the person, in list order, by the rules of moving one claim. Each
     * is moved in its own transaction: one that is refused, or is not a claim of the sheet, is left as it is and
     * counted as refused.
     */
    public Result reassign(String by, String sheetId, Set<Long> ids, Long toPersonId) {
        return each(by, sheetId, ids, id -> claims.reassign(by, sheetId, id, toPersonId, true));
    }

    /**
     * Applies the change to each of the sheet's claims among the ids in list order, counting the refused ones. A
     * viewer, or anyone on an archived sheet, changes none: every one is refused.
     */
    private Result each(String by, String sheetId, Set<Long> ids, Function<Long, Claim> change) {
        if (settings.isReadOnly(sheetId, by)) {
            return new Result(List.of(), ids.size());
        }
        List<Long> ordered = ordered(sheetId, ids);
        List<Claim> changed = new ArrayList<>();
        int refused = ids.size() - ordered.size();
        for (Long id : ordered) {
            try {
                changed.add(change.apply(id));
            } catch (ResponseStatusException e) {
                refused++;
            }
        }
        return new Result(changed, refused);
    }

    /** The ids of the sheet's claims among the ids, in list order (the order of people, newest claim first). */
    private List<Long> ordered(String sheetId, Set<Long> ids) {
        Map<Long, Integer> personOrder = new HashMap<>();
        for (Person p : people.findBySheetIdOrderByIdAsc(sheetId)) {
            personOrder.put(p.getId(), personOrder.size());
        }
        return repository.findBySheetIdOrderByListedDescIdDesc(sheetId).stream()
                .filter(c -> ids.contains(c.getId()))
                .sorted(Comparator.comparing((Claim c) -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE))
                        .thenComparing(Claim::getListed, Comparator.reverseOrder())
                        .thenComparing(Claim::getId, Comparator.reverseOrder()))
                .map(Claim::getId).toList();
    }
}
