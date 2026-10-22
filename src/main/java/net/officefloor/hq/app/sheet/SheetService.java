package net.officefloor.hq.app.sheet;

import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.rollup.Rollups;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; an id never loaded before starts a new, empty sheet. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Rollups rollups;

    public SheetService(SheetRepository sheets, Rollups rollups) {
        this.sheets = sheets;
        this.rollups = rollups;
    }

    /** Load a sheet; a new sheet's today starts as the real date. */
    @Transactional
    public synchronized Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> sheets.save(new Sheet(id, Instant.now(), LocalDate.now())));
    }

    /**
     * The sheet's today. Every date-dependent decision and every default date on a sheet uses
     * this, never the real clock.
     */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return load(id).getToday();
    }

    /**
     * Give out the sheet's next claim number, holding the sheet's lock until the caller's
     * transaction ends so two claims opened at once get different numbers.
     */
    @Transactional
    public int nextClaimNumber(String id) {
        load(id);
        return sheets.findLockedById(id).orElseThrow().nextClaimNumber();
    }

    /**
     * Take the sheet's lock, holding it until the caller's transaction ends, so changes that check
     * the sheet's other rows before writing are made one at a time.
     */
    @Transactional
    public Sheet lock(String id) {
        load(id);
        return sheets.findLockedById(id).orElseThrow();
    }

    /** Change the sheet's today, auditing the figures that moves. */
    @Transactional
    public synchronized Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id);
        Rollups.Rollup before = rollups.take(id);
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        rollups.audit(id, user, before);
        return saved;
    }
}
