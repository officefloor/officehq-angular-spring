package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Changes to many selected claims at once. Each claim is changed in its own transaction, in list
 * order (people in their list positions, each person's claims newest first), exactly as if
 * its own button were pressed; one that is refused is left as it is and counted as refused (a bulk
 * settle is instead all or nothing). A
 * viewer's bulk change, or one on an archived sheet, goes through with nothing done: every selected
 * claim is refused.
 */
@Service
public class ClaimBulkService {

    /** How many of the selected claims went through, and how many were refused. */
    public record Result(int done, int refused) {}

    private final ClaimService claims;
    private final ClaimRepository repository;
    private final SettingService settings;
    private final SheetService sheets;
    private final PersonRepository people;
    private final Audit audit;

    public ClaimBulkService(ClaimService claims, ClaimRepository repository, SettingService settings, SheetService sheets,
            PersonRepository people, Audit audit) {
        this.people = people;
        this.audit = audit;
        this.claims = claims;
        this.repository = repository;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Open each selected claim that can be opened, numbering them in list order. */
    public Result open(String sheetId, String user, Set<Long> ids) {
        return change(sheetId, user, ids, id -> claims.changeStatus(sheetId, user, id, ClaimStatus.OPEN));
    }

    /**
     * Settle the selected claims, all or nothing, by the rules of settling one: a claim over the
     * user's clerk limit goes to PENDING and counts as settled. If any is refused, none is settled,
     * every one counts as refused and nothing is written. Otherwise they are settled in list order as
     * one batch: the sheet's next batch number, given to each claim, then one BATCH_SETTLED line with
     * the number of claims and the frozen figures of those SETTLED added up.
     */
    @Transactional
    public Result settle(String sheetId, String user, Set<Long> ids) {
        if (refusesAll(sheetId, user)) return new Result(0, ids.size());
        List<Claim> selected = selected(sheetId, ids);
        if (selected.size() != ids.size()) return new Result(0, ids.size());
        Integer batch = null;
        BigDecimal total = BigDecimal.ZERO;
        for (Claim claim : selected) {
            try {
                Claim settled = claims.settleInBatch(sheetId, user, claim.getId(), batch);
                batch = settled.getSettleBatch();
                if (settled.getStatus() == ClaimStatus.SETTLED && settled.getSettledDue() != null) {
                    total = total.add(settled.getSettledDue());
                }
            } catch (ClaimStatusRefusedException | ClaimSettledByOpenerException | ClaimDeferredException | ClaimProofRequiredException
                    | ClaimNoteRequiredException
                    | ClaimNotPermittedException
                    | PeriodClosedException
                    | ClaimNotFoundException | PersonNotFoundException refused) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new Result(0, ids.size());
            }
        }
        if (batch != null) {
            audit.recordOnCommit("BATCH_SETTLED sheet=" + sheetId + " by=" + user.strip() + " batch=" + batch
                    + " count=" + selected.size() + " total=" + total.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        return new Result(selected.size(), 0);
    }

    /** The number of the sheet's last settle batch, or null while there has been none. */
    public Integer lastSettleBatch(String sheetId) {
        int last = sheets.lastSettleBatch(sheetId);
        return last == 0 ? null : last;
    }

    /**
     * Move each selected claim that can be moved to the person, by the rules of moving one (who
     * may, which statuses, closed periods, retirement), with its own CLAIM_REASSIGNED and rollup lines.
     */
    public Result reassign(String sheetId, String user, Set<Long> ids, Long toPersonId) {
        if (refusesAll(sheetId, user)) return new Result(0, ids.size());
        int done = 0;
        for (Claim claim : selected(sheetId, ids)) {
            try {
                claims.reassign(sheetId, user, claim.getId(), toPersonId, true);
                done++;
            } catch (ClaimStatusRefusedException | ClaimNotPermittedException | ClaimReassignRefusedException
                    | PeriodClosedException | PersonRetiredException
                    | ClaimNotFoundException | PersonNotFoundException refused) {
                // left as it is
            }
        }
        return new Result(done, ids.size() - done);
    }

    /**
     * Re-date each selected claim that can be re-dated, by the rules of re-dating one (who may, which
     * statuses, closed periods, retirement, renumbering on a period change), with its own CLAIM_REDATED line.
     */
    public Result redate(String sheetId, String user, Set<Long> ids, LocalDate date) {
        if (refusesAll(sheetId, user)) return new Result(0, ids.size());
        int done = 0;
        for (Claim claim : selected(sheetId, ids)) {
            try {
                claims.redate(sheetId, user, claim.getId(), date);
                done++;
            } catch (ClaimStatusRefusedException | ClaimNotStewardException | PeriodClosedException
                    | PersonRetiredException | ClaimNotFoundException | PersonNotFoundException refused) {
                // left as it is
            }
        }
        return new Result(done, ids.size() - done);
    }

    private boolean refusesAll(String sheetId, String user) {
        return settings.roleOf(sheetId, user) == Role.VIEWER || sheets.isArchived(sheetId);
    }

    /** The selected claims in list order: people in their list positions, each person's newest first. */
    private List<Claim> selected(String sheetId, Set<Long> ids) {
        Map<Long, Integer> place = new HashMap<>();
        List<Person> list = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        for (int i = 0; i < list.size(); i++) place.put(list.get(i).getId(), i);
        return repository.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> ids.contains(c.getId()))
                .sorted(Comparator.comparing((Claim c) -> place.getOrDefault(c.getPersonId(), Integer.MAX_VALUE))
                        .thenComparing(Claim::getPersonId)
                        .thenComparing(Claim::getListed, Comparator.reverseOrder()))
                .toList();
    }

    private Result change(String sheetId, String user, Set<Long> ids, Consumer<Long> change) {
        if (refusesAll(sheetId, user)) return new Result(0, ids.size());
        int done = 0;
        for (Claim claim : selected(sheetId, ids)) {
            try {
                change.accept(claim.getId());
                done++;
            } catch (ClaimStatusRefusedException | ClaimSettledByOpenerException | ClaimDeferredException | ClaimProofRequiredException
                    | ClaimNoteRequiredException
                    | ClaimNotPermittedException
                    | PeriodClosedException
                    | ClaimNotFoundException | PersonNotFoundException refused) {
                // left as it is
            }
        }
        return new Result(done, ids.size() - done);
    }
}
