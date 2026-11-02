package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Changes made to many claims at once; each claim is changed in its own transaction, so one that
 *  is refused leaves the others as they are, except a settle, which is all or nothing; on an
 *  archived sheet each is refused. */
@Service
public class ClaimBulkService {

    /** How many claims a bulk change made, and how many it refused. */
    public record Outcome(int done, int refused) {
    }

    private final ClaimService claims;
    private final ClaimRepository repository;
    private final PersonRepository people;
    private final SheetService sheets;
    private final TransactionTemplate transactions;
    private final Audit audit;

    public ClaimBulkService(ClaimService claims, ClaimRepository repository, PersonRepository people,
            SheetService sheets, PlatformTransactionManager transactionManager, Audit audit) {
        this.claims = claims;
        this.transactions = new TransactionTemplate(transactionManager);
        this.audit = audit;
        this.sheets = sheets;
        this.repository = repository;
        this.people = people;
    }

    /** Open each of the claims that can be opened, numbering them in list order (people in the
     *  order they were added, newest claim first within a person); each that cannot is refused and
     *  left as it is, and so is an id that names no claim on the sheet. */
    public Outcome open(String sheetId, String user, Set<Long> claimIds) {
        return change(sheetId, user, claimIds, ClaimStatus.OPEN);
    }

    /** Settle all the claims or none: when any of them cannot be settled by the user (any refusal
     *  settling one would give; one a clerk's limit leaves pending is not refused), or an id names no
     *  claim on the sheet, none is settled, each counts as refused and nothing is written. Otherwise
     *  each is settled in list order, exactly as settling one, and they are the sheet's next settle
     *  batch, recorded after their own lines with the frozen dues of those settled added up. */
    public Outcome settle(String sheetId, String user, Set<Long> claimIds) {
        if (sheets.archived(sheetId) || claimIds.isEmpty()) {
            return new Outcome(0, claimIds.size());
        }
        Boolean done = transactions.execute(status -> {
            List<Claim> listed = inListOrder(sheetId, claimIds);
            if (listed.size() != claimIds.size()) {
                status.setRollbackOnly();
                return false;
            }
            List<Long> settled = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (Claim claim : listed) {
                Claim saved;
                try {
                    saved = claims.changeStatus(sheetId, user, claim.getId(), ClaimStatus.SETTLED);
                } catch (ResponseStatusException refused) {
                    // one refused: none is settled, and nothing is written
                    status.setRollbackOnly();
                    return false;
                }
                settled.add(saved.getId());
                if (saved.getStatus() == ClaimStatus.SETTLED && saved.getSettledDue() != null) {
                    total = total.add(saved.getSettledDue());
                }
            }
            // the claims it settled are a batch, numbered next on the sheet
            int batch = claims.markSettleBatch(sheetId, settled);
            audit.recordOnCommit("BATCH_SETTLED sheet=" + sheetId + " by=" + user + " batch=" + batch
                    + " count=" + settled.size() + " total=" + total.setScale(2, RoundingMode.HALF_UP).toPlainString());
            return true;
        });
        return Boolean.TRUE.equals(done) ? new Outcome(claimIds.size(), 0) : new Outcome(0, claimIds.size());
    }

    /** Move each of the claims that can be moved to the person, in list order, exactly as moving
     *  one; each that cannot is refused and left as it is, and so is an id that names no claim on the
     *  sheet. */
    public Outcome reassign(String sheetId, String user, Set<Long> claimIds, long toPersonId) {
        if (sheets.archived(sheetId)) {
            return new Outcome(0, claimIds.size());
        }
        int done = 0;
        for (Claim claim : inListOrder(sheetId, claimIds)) {
            try {
                claims.reassign(sheetId, user, claim.getId(), toPersonId, true);
                done++;
            } catch (ResponseStatusException refused) {
                // left as it is
            }
        }
        return new Outcome(done, claimIds.size() - done);
    }

    private Outcome change(String sheetId, String user, Set<Long> claimIds, ClaimStatus to) {
        if (sheets.archived(sheetId)) {
            return new Outcome(0, claimIds.size());
        }
        int done = 0;
        for (Claim claim : inListOrder(sheetId, claimIds)) {
            try {
                claims.changeStatus(sheetId, user, claim.getId(), to);
                done++;
            } catch (ResponseStatusException refused) {
                // left as it is
            }
        }
        return new Outcome(done, claimIds.size() - done);
    }

    /** The claims of the ids on the sheet in list order: people in the order they were added, newest
     *  claim first within a person. */
    private List<Claim> inListOrder(String sheetId, Set<Long> claimIds) {
        Map<Long, Integer> rank = new HashMap<>();
        List<Person> listed = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < listed.size(); i++) {
            rank.put(listed.get(i).getId(), i);
        }
        return repository.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> claimIds.contains(c.getId()))
                .sorted(Comparator.comparingInt((Claim c) -> rank.getOrDefault(c.getPersonId(), Integer.MAX_VALUE))
                        .thenComparing(Claim::getListed, Comparator.reverseOrder()))
                .toList();
    }
}
