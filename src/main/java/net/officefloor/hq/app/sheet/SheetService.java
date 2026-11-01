package net.officefloor.hq.app.sheet;

import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a sheet by id; an id never used before starts a new, empty sheet whose today is the real
 * date. After that the sheet's today is the only date the app uses for it ({@link #today}).
 */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final SheetParentRepository parents;
    private final RollupService rollup;
    // looked up when used: the periods depend on the claims, which depend on the sheet
    private final ObjectProvider<PeriodService> periods;
    private final ObjectProvider<SettingService> settings;
    private final ObjectProvider<ClaimRepository> claims;
    private final Audit audit;

    public SheetService(SheetRepository sheets, SheetParentRepository parents, RollupService rollup, ObjectProvider<PeriodService> periods,
            ObjectProvider<SettingService> settings, ObjectProvider<ClaimRepository> claims, Audit audit) {
        this.claims = claims;
        this.sheets = sheets;
        this.parents = parents;
        this.rollup = rollup;
        this.periods = periods;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(), LocalDate.now()));
            } catch (DataIntegrityViolationException raced) {
                // another user started the same sheet at the same moment
                return sheets.findById(id).orElseThrow(() -> raced);
            }
        });
    }

    /** The sheet's today: use this, never the real clock, for any date decision or default date. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseThrow(SheetNotFoundException::new);
    }

    /**
     * Lock the sheet for numbering a claim: its sheet-wide numbers are 1, 2, ... with no gaps and never
     * reused, and so are its numbers within each period. The sheet stays locked until the caller's
     * transaction ends, so two users opening claims at once get different numbers.
     */
    @Transactional
    public Sheet lockForNumbering(String id) {
        return sheets.findForUpdate(id).orElseThrow(SheetNotFoundException::new);
    }

    /**
     * The last sheet-wide claim number given on any sheet of the sheet's tree (its topmost ancestor and every
     * descendant of that); 0 if none. The tree's topmost sheet is locked too, as well as the caller's own
     * (see {@link #lockForNumbering}), so two users opening claims on sheets of one tree get different numbers.
     */
    @Transactional
    public long lastClaimNumberInTree(String id) {
        String root = parents.rootOf(id);
        if (!root.equals(id)) sheets.findForUpdate(root);
        return sheets.lastClaimNumberAmong(parents.treeOf(id));
    }

    /**
     * Change the sheet's today; it has no audit line of its own, but a new today first audits a SNAPSHOT
     * line per person, in list order, with their due-total on the day being left. With grace days set, the open periods
     * past them are closed first, then the figures it moves are audited. A new today releases every
     * claim editor's lock on the sheet.
     */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = sheets.findForUpdate(id).orElseThrow(SheetNotFoundException::new);
        boolean changed = !today.equals(sheet.getToday());
        if (changed) {
            claims.getObject().unlockAll(id);
            sheet = sheets.findForUpdate(id).orElseThrow(SheetNotFoundException::new);
        }
        RollupService.Snapshot before = rollup.snapshot(id);
        if (changed) rollup.recordSnapshot(id, user, before);
        sheet.setToday(today);
        sheets.flush();
        if (sheet.getGraceDays() != null) periods.getObject().autoClose(id, user, today, sheet.getGraceDays());
        rollup.recordChanges(id, user, before);
        return sheet;
    }

    /** Archive the sheet, as a steward (or one acting for a steward); anyone else is refused. */
    @Transactional
    public Sheet archive(String id, String user) {
        Sheet sheet = sheets.findForUpdate(id).orElseThrow(SheetNotFoundException::new);
        if (sheet.isArchived()) throw new SheetArchivedException();
        if (settings.getObject().roleOf(id, user) != Role.STEWARD) throw new SheetArchiveRefusedException();
        sheet.archive();
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + id + " by=" + user);
        return sheet;
    }

    /** The number of the sheet's last settle batch; 0 while there has been none. */
    @Transactional(readOnly = true)
    public int lastSettleBatch(String id) {
        return sheets.findById(id).map(Sheet::getLastSettleBatch).orElse(0);
    }

    /** Whether the sheet has been archived: every change to it is refused. */
    @Transactional(readOnly = true)
    public boolean isArchived(String id) {
        return sheets.findById(id).map(Sheet::isArchived).orElse(false);
    }
}
