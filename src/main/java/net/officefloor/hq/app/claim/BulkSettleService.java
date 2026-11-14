package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.setting.PermissionRefusedException;
import net.officefloor.hq.app.setting.SettingService;
import java.util.Set;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.Audit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** Settles a selection of claims together. */
@Service
public class BulkSettleService {

    /** What a bulk settle did: the claims it settled (SETTLED, or PENDING a steward's approval), in
     *  the order it settled them, how many of the selected claims it refused, and the number of the
     *  settle batch it made (null when it settled none). */
    public record Result(List<Claim> settled, int refused, Long batch) {
    }

    private final ClaimListOrder order;
    private final ClaimService service;
    private final RollupService rollups;
    private final SettingService settings;
    private final SheetService sheets;
    private final Audit audit;

    public BulkSettleService(ClaimListOrder order, ClaimService service, RollupService rollups, SettingService settings,
            SheetService sheets, Audit audit) {
        this.order = order;
        this.service = service;
        this.rollups = rollups;
        this.settings = settings;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** Settle the selected claims all or nothing, in list order (people in the order they were
     *  added, each person's claims newest first), by exactly the rules of settling one: a clerk's
     *  claim above the clerk limit goes PENDING and counts as settled. When any one cannot be
     *  settled, or is not on the sheet, none is: every selected claim counts as refused and nothing
     *  is written. Otherwise they make a settle batch, whose number each marks, and BATCH_SETTLED is
     *  written after the claims' own lines. */
    @Transactional
    public Result settle(String sheetId, String user, Set<Long> ids) {
        Result none = new Result(List.of(), ids.size(), null);
        if (settings.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: every selected claim is refused.
            return none;
        }
        List<Claim> selected = order.selected(sheetId, ids);
        if (selected.size() != ids.size()) {
            // A selected claim not on the sheet is refused, so all of them are.
            return none;
        }
        List<Claim> settled = new ArrayList<>();
        try {
            for (Claim claim : selected) {
                settled.add(rollups.track(sheetId, user, () -> service.changeStatus(sheetId, user,
                        claim.getPersonId(), claim.getId(), ClaimStatus.SETTLED)));
            }
        } catch (StatusChangeRefusedException | PermissionRefusedException | OwnClaimSettleRefusedException | ProofRequiredException | PeriodClosedException
                | NoSuchElementException refused) {
            // One refused: roll back every claim this settle changed, with the audit lines they held.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return none;
        }
        if (settled.isEmpty()) {
            return new Result(settled, 0, null);
        }
        long batch = service.batch(sheetId, settled);
        BigDecimal total = settled.stream()
                .filter(claim -> claim.getStatus() == ClaimStatus.SETTLED && claim.getSettledDue() != null)
                .map(Claim::getSettledDue).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        audit.recordOnCommit("BATCH_SETTLED sheet=" + sheetId + " by=" + user + " batch=" + batch + " count="
                + settled.size() + " total=" + total.toPlainString());
        return new Result(settled, 0, batch);
    }
}
