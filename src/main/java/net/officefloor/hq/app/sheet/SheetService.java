package net.officefloor.hq.app.sheet;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
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

    /** Change the sheet's today. */
    @Transactional
    public Sheet setToday(String id, LocalDate today) {
        Sheet sheet = load(id);
        sheet.setToday(today);
        return sheets.saveAndFlush(sheet);
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
