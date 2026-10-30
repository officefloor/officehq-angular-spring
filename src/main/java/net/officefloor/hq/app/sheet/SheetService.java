package net.officefloor.hq.app.sheet;

import java.time.Clock;
import java.time.LocalDate;
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
    private final Clock clock = Clock.systemDefaultZone();

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
    }

    public Sheet load(String id, String user) {
        try {
            return loadOrCreate(id, user);
        } catch (DataIntegrityViolationException e) {
            // Another user started the same sheet at the same moment: theirs is the one.
            return sheets.findById(id).orElseThrow(() -> e);
        }
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

    /** Change the sheet's today, starting the sheet if it was never loaded. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        sheet.setToday(today);
        return sheets.save(sheet);
    }

    private Sheet loadOrCreate(String id, String user) {
        return sheets.findById(id)
                .orElseGet(() -> sheets.saveAndFlush(new Sheet(id, user, LocalDate.now(clock))));
    }
}
