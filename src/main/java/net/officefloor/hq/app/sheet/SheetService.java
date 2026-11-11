package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a sheet by id, starting an empty one the first time the id is used, and keeps the sheet's
 * today. The real clock is read only once, to give a new sheet its starting today; everything else
 * dated uses {@link #today(String)}.
 */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollup;
    private final PeriodService periods;

    public SheetService(SheetRepository sheets, RollupService rollup, PeriodService periods) {
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
    }

    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> start(id, user));
    }

    public Sheet get(String id) {
        return sheets.findById(id).orElseThrow(() -> new SheetNotFoundException(id));
    }

    /** The sheet's today: use it for every date-dependent decision and default date. */
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseThrow(() -> new SheetNotFoundException(id));
    }

    /**
     * Change the sheet's today; it has no audit record of its own. Every open period past its grace days on the new
     * today then closes, before the figures it moved are recorded.
     */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = sheets.findById(id).orElseThrow(() -> new SheetNotFoundException(id));
        RollupService.Snapshot before = rollup.snapshot(id);
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        periods.autoClose(id, user, today, saved.getGraceDays());
        rollup.recordChanges(id, user, before);
        return saved;
    }

    /**
     * The next claim number on the sheet: 1 for the first, then 2, and so on. The sheet's row is
     * locked until the caller's transaction ends, so two claims opened at once get different numbers.
     */
    @Transactional
    public long nextClaimNumber(String id) {
        if (sheets.incrementLastClaimNumber(id) != 1) {
            throw new SheetNotFoundException(id);
        }
        return sheets.lastClaimNumber(id);
    }

    /**
     * The next place in the sheet's claim lists: a claim with a later place is listed before one with an earlier
     * place. The sheet's row is locked until the caller's transaction ends.
     */
    @Transactional
    public long nextListed(String id) {
        if (sheets.incrementLastListed(id) != 1) {
            throw new SheetNotFoundException(id);
        }
        return sheets.lastListed(id);
    }

    /**
     * The next number in a sequence of its own on the sheet (a period's, or X for no period): the key, a dash, then
     * 1 for the first, then 2, and so on. The sheet's row is locked until the caller's transaction ends.
     */
    @Transactional
    public String nextSequenceNumber(String id, String key) {
        if (sheets.lockById(id) != 1) {
            throw new SheetNotFoundException(id);
        }
        sheets.incrementSequence(id, key);
        return key + "-" + sheets.lastSequenceNumber(id, key);
    }

    private Sheet start(String id, String user) {
        try {
            return sheets.saveAndFlush(new Sheet(id, user, LocalDate.now()));
        } catch (DataIntegrityViolationException e) {
            // Another user started the same sheet at the same moment: it exists now.
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }
}
