package net.officefloor.hq.app.sheet;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet never loaded before starts empty, its today the real date. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Clock clock;

    public SheetService(SheetRepository sheets, Clock clock) {
        this.sheets = sheets;
        this.clock = clock;
    }

    /** The sheet with this id, created empty when it was never loaded before. */
    public Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, LocalDate.now(clock)));
            } catch (DataIntegrityViolationException e) {
                return sheets.findById(id).orElseThrow(() -> e); // created concurrently
            }
        });
    }

    /** The sheet's today: the date every date-dependent decision and default on the sheet uses. */
    public LocalDate today(String id) {
        return load(id).getToday();
    }

    /** Change the sheet's today. */
    @Transactional
    public Sheet setToday(String id, LocalDate today) {
        Sheet sheet = load(id);
        sheet.setToday(today);
        return sheets.save(sheet);
    }
}
