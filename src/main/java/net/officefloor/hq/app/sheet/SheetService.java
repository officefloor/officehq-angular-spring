package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads a sheet by id; a sheet never loaded before starts empty. */
@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
    }

    public Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> create(id));
    }

    /** Change the sheet's today; every date decision on the sheet follows it. */
    @Transactional
    public Sheet setToday(String id, LocalDate today) {
        Sheet sheet = load(id);
        sheet.setToday(today);
        return sheets.saveAndFlush(sheet);
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
