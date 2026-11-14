package net.officefloor.hq.app.sheet;

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

    /** Load a sheet by id; an id never used before starts an empty sheet, its today the real date. */
    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, LocalDate.now()));
            } catch (DataIntegrityViolationException e) {
                // another user created it at the same moment
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }

    /** The sheet's today: the date every date decision and default date on the sheet uses. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(LocalDate::now);
    }

    /** Take the sheet's next claim number (1 for the first), holding the sheet locked until the
     *  transaction ends so numbers are never shared and, if it rolls back, never skipped. */
    @Transactional
    public int nextClaimNumber(String id) {
        Sheet sheet = sheets.findForUpdate(id).orElseThrow();
        int number = sheet.nextClaimNumber();
        sheets.flush();
        return number;
    }

    /** Change the sheet's today (the sheet is created first if it was never loaded). */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        sheet.setToday(today);
        return sheet;
    }
}
