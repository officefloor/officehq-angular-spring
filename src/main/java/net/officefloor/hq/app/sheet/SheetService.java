package net.officefloor.hq.app.sheet;

import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.rollup.RollupService;
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
    private final RollupService rollup;
    // looked up when used: the periods depend on the claims, which depend on the sheet
    private final ObjectProvider<PeriodService> periods;

    public SheetService(SheetRepository sheets, RollupService rollup, ObjectProvider<PeriodService> periods) {
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
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
     * Change the sheet's today; it has no audit line of its own. With grace days set, the open periods
     * past them are closed first, then the figures it moves are audited.
     */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = sheets.findForUpdate(id).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(id);
        sheet.setToday(today);
        sheets.flush();
        if (sheet.getGraceDays() != null) periods.getObject().autoClose(id, user, today, sheet.getGraceDays());
        rollup.recordChanges(id, user, before);
        return sheet;
    }
}
