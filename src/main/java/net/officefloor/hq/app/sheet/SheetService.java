package net.officefloor.hq.app.sheet;

import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet never loaded before is started empty, its today the real date. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Rollup rollup;

    public SheetService(SheetRepository sheets, Rollup rollup) {
        this.sheets = sheets;
        this.rollup = rollup;
    }

    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(), LocalDate.now()));
            } catch (DataIntegrityViolationException e) {
                // Another user started the same sheet at the same moment.
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }

    /** The sheet's today. Every date decision and default date in the sheet goes through this. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseThrow(SheetNotFoundException::new);
    }

    /** Change the sheet's today, recording the figures it moves. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = sheets.findById(id).orElseThrow(SheetNotFoundException::new);
        Rollup.Snapshot before = rollup.take(id);
        sheet.setToday(today);
        rollup.recordChanges(id, user, before);
        return sheet;
    }

    /** The sheet's next claim number: 1 for the first, then 2, and so on, never given twice. */
    @Transactional
    public int nextClaimNumber(String id) {
        return sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new).nextClaimNumber();
    }
}
