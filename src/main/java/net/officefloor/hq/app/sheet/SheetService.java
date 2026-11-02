package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet that was never loaded before starts empty. */
@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
    }

    /** The sheet with this id, created empty the first time it is loaded, its today the real date. */
    @Transactional
    public synchronized Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> sheets.save(new Sheet(id, user, LocalDate.now())));
    }

    /** The sheet's today: the date every date-dependent decision and default date on it uses. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(LocalDate::now);
    }

    /** The next claim number on the sheet: 1 for its first opened claim, then 2, and so on. */
    @Transactional
    public int nextClaimNumber(String id) {
        if (sheets.advanceClaimNumber(id) != 1) {
            throw new IllegalStateException("No sheet " + id);
        }
        return sheets.lastClaimNumber(id);
    }

    /** Change the sheet's today. */
    @Transactional
    public synchronized Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        sheet.setToday(today);
        return sheets.save(sheet);
    }
}
