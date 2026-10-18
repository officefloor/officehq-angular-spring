package net.officefloor.hq.app.sheet;

import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollups;

    public SheetService(SheetRepository sheets, RollupService rollups) {
        this.sheets = sheets;
        this.rollups = rollups;
    }

    /** The sheet with this id; a sheet never loaded before is started empty, today at the real date. */
    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(), LocalDate.now()));
            } catch (DataIntegrityViolationException raced) {
                return sheets.findById(id).orElseThrow(() -> raced);
            }
        });
    }

    /**
     * The sheet's "today". Every decision that depends on the date, and every default date, uses
     * this and never the real clock.
     */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(LocalDate::now);
    }

    /**
     * Change the sheet's "today" (the sheet is started when it was never loaded before). It has no
     * audit line of its own, but is followed by the rollup lines of the figures it moves.
     */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = lock(id, user);
        RollupService.Rollup before = rollups.snapshot(id);
        sheet.setToday(today);
        sheets.flush();
        rollups.recordChanges(id, user, before);
        return sheet;
    }

    /**
     * Lock the sheet until the current transaction ends, so its claims' statuses change one at a
     * time (the sheet is started when it was never loaded before).
     */
    @Transactional
    public Sheet lock(String id, String user) {
        return sheets.findByIdForUpdate(id).orElseGet(() -> {
            load(id, user);
            return sheets.findByIdForUpdate(id).orElseThrow();
        });
    }
}
