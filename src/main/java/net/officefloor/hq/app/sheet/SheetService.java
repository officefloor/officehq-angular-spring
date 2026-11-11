package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a sheet by id, starting an empty one the first time the id is used, and keeps the sheet's
 * today. The real clock is read only once, to give a new sheet its starting today; everything else
 * dated uses {@link #today(String)}.
 */
@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
    }

    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> start(id, user));
    }

    /** The sheet's today: use it for every date-dependent decision and default date. */
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseThrow(() -> new SheetNotFoundException(id));
    }

    @Transactional
    public Sheet setToday(String id, LocalDate today) {
        Sheet sheet = sheets.findById(id).orElseThrow(() -> new SheetNotFoundException(id));
        sheet.setToday(today);
        return sheets.saveAndFlush(sheet);
    }

    private Sheet start(String id, String user) {
        try {
            return sheets.saveAndFlush(new Sheet(id, user, LocalDate.now()));
        } catch (DataIntegrityViolationException e) {
            // Another user started the same sheet at the same moment: it exists now.
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }
}
