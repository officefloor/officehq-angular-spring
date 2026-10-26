package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads a sheet by id; a sheet never loaded before starts empty. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollup;

    public SheetService(SheetRepository sheets, RollupService rollup) {
        this.sheets = sheets;
        this.rollup = rollup;
    }

    public Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> create(id));
    }

    /**
     * Change the sheet's today; every date decision on the sheet follows it. It has no audit line of its own, only
     * the figures it moves.
     */
    @Transactional
    public Sheet setToday(String by, String id, LocalDate today) {
        Sheet sheet = load(id);
        RollupService.Snapshot before = rollup.snapshot(id);
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        rollup.recordChanges(by, id, before);
        return saved;
    }

    /** Takes the sheet's next claim number; two callers at once never get the same one. */
    @Transactional
    public long nextClaimNumber(String id) {
        load(id);
        sheets.incrementClaimNumber(id);
        return sheets.lastClaimNumber(id);
    }

    private Sheet create(String id) {
        try {
            return sheets.saveAndFlush(new Sheet(id));
        } catch (DataIntegrityViolationException e) {
            // another user created it at the same moment
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }
}
