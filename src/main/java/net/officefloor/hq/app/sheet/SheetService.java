package net.officefloor.hq.app.sheet;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; an id never loaded before starts a new, empty sheet. */
@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
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

    /** Change the sheet's today. */
    @Transactional
    public synchronized Sheet setToday(String id, LocalDate today) {
        Sheet sheet = load(id);
        sheet.setToday(today);
        return sheets.save(sheet);
    }
}
