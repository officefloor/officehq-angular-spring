package net.officefloor.hq.app.sheet;

import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Rollup rollup;

    public SheetService(SheetRepository sheets, Rollup rollup) {
        this.sheets = sheets;
        this.rollup = rollup;
    }

    /** The sheet with this id, as the store holds it now; a sheet never loaded before starts empty. */
    @Transactional
    public Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> create(id));
    }

    /**
     * The sheet's today. Every decision that depends on the date, and every default date, uses this
     * and never the real clock.
     */
    @Transactional
    public LocalDate today(String id) {
        return load(id).getToday();
    }

    /** Change the sheet's today; it has no audit line of its own, only the figures it moves. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id);
        Rollup.Snapshot before = rollup.snapshot(id);
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        rollup.recordChanges(user, before);
        return saved;
    }

    /**
     * The sheet's next claim number: 1 for its first opened claim, then 2, and so on. The sheet stays
     * locked until the transaction ends, so two claims opened at once never share a number.
     */
    @Transactional
    public int nextClaimNumber(String id) {
        load(id);
        sheets.takeClaimNumber(id);
        return sheets.claimNumbersUsed(id);
    }

    /**
     * The next number of one of the sheet's per-period sequences (by key): 1 for its first, then 2,
     * and so on. The sheet stays locked until the transaction ends, so two claims opened at once
     * never share a number.
     */
    @Transactional
    public int nextSequenceNumber(String id, String key) {
        load(id);
        sheets.findForUpdate(id);
        Integer used = sheets.sequenceNumbersUsed(id, key);
        if (used == null) {
            sheets.startSequence(id, key);
            return 1;
        }
        sheets.takeSequenceNumber(id, key);
        return used + 1;
    }

    private Sheet create(String id) {
        try {
            // The only use of the real clock: a new sheet's today starts as the real date.
            return sheets.saveAndFlush(new Sheet(id, Instant.now(), LocalDate.now()));
        } catch (DataIntegrityViolationException raced) {
            // Another user loaded the same new id at the same moment.
            return sheets.findById(id).orElseThrow(() -> raced);
        }
    }
}
