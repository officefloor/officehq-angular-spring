package net.officefloor.hq.app.sheet;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads sheets by id; a sheet that was never loaded before is started empty, its today set to the
 * real date. From then on the sheet's today, not the clock, is the date everything on it uses:
 * call {@link #today(String)} wherever a decision or a default depends on the date.
 */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollup;
    private final Clock clock = Clock.systemDefaultZone();

    public SheetService(SheetRepository sheets, RollupService rollup) {
        this.sheets = sheets;
        this.rollup = rollup;
    }

    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(clock), LocalDate.now(clock)));
            } catch (DataIntegrityViolationException e) {
                // Another user started the same sheet at the same moment.
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }

    /** The sheet's today: the date every date-dependent decision and default on it uses. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseThrow(SheetNotFoundException::new);
    }

    /** Whether a claim opened now on the sheet is numbered within its period (see {@link Sheet#numbersByPeriod()}). */
    @Transactional(readOnly = true)
    public boolean numbersByPeriod(String id) {
        return sheets.findById(id).map(Sheet::numbersByPeriod).orElseThrow(SheetNotFoundException::new);
    }

    /** Change the sheet's today; the figures it moves are recorded as {@code user}'s change. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = sheets.findById(id).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(id);
        sheet.setToday(today);
        sheets.flush();
        rollup.recordSince(id, user, before);
        return sheet;
    }

    /** The next claim number on the sheet: 1 for its first, then 2, and so on, never given twice. */
    @Transactional
    public int nextClaimNumber(String id) {
        if (sheets.advanceClaimNumber(id) == 0) throw new SheetNotFoundException();
        return sheets.lastClaimNumber(id);
    }

    /** The next number in the sheet's sequence named {@code key}: 1 for its first, then 2, and so on,
     * never given twice. */
    @Transactional
    public int nextInSequence(String id, String key) {
        if (sheets.lockSheet(id) == 0) throw new SheetNotFoundException();
        if (sheets.advanceSequence(id, key) == 0) sheets.startSequence(id, key);
        return sheets.lastInSequence(id, key);
    }
}
