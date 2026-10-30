package net.officefloor.hq.app.sheet;

import java.time.Clock;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a sheet by id, starting an empty one when the id was never used, and keeps the sheet's
 * today. The real clock is read only to start a new sheet's today; everything else that depends
 * on the date asks {@link #today(String)}.
 */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Rollup rollup;
    private final Clock clock = Clock.systemDefaultZone();

    public SheetService(SheetRepository sheets, Rollup rollup) {
        this.sheets = sheets;
        this.rollup = rollup;
    }

    public Sheet load(String id, String user) {
        try {
            return loadOrCreate(id, user);
        } catch (DataIntegrityViolationException e) {
            // Another user started the same sheet at the same moment: theirs is the one.
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }

    /** The sheet's floor: the least live due a claim has. */
    public BigDecimal floor(String id) {
        return sheets.findById(id).map(Sheet::getFloor).orElse(Sheet.DEFAULT_FLOOR);
    }

    /** The sheet's terms: the days a claim has to be paid by, from its date. */
    public int terms(String id) {
        return sheets.findById(id).map(Sheet::getTerms).orElse(Sheet.DEFAULT_TERMS);
    }

    /** The sheet's today: the date to use for every decision and default date on the sheet. */
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(() -> LocalDate.now(clock));
    }

    /**
     * The next claim number on the sheet: 1 for the first, then 2, and so on. Two users taking one
     * at the same moment wait on each other, so no number is given twice or skipped.
     */
    @Transactional
    public int nextClaimNumber(String id) {
        if (sheets.bumpClaimNumber(id) != 1) {
            throw new IllegalStateException("No such sheet " + id);
        }
        return sheets.lastClaimNumber(id);
    }

    /**
     * Change the sheet's today, starting the sheet if it was never loaded. The change has no audit
     * line of its own, only the figures it moves.
     */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        Rollup.Figures before = rollup.figures(id);
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        rollup.changed(id, user, before);
        return saved;
    }

    private Sheet loadOrCreate(String id, String user) {
        return sheets.findById(id)
                .orElseGet(() -> sheets.saveAndFlush(new Sheet(id, user, LocalDate.now(clock))));
    }
}
