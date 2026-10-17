package net.officefloor.hq.app.sheet;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet never loaded before is created empty. */
@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
    }

    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> create(id, user));
    }

    private Sheet create(String id, String user) {
        try {
            return sheets.saveAndFlush(new Sheet(id, user));
        } catch (DataIntegrityViolationException e) {
            // Another user loaded the same new sheet at the same moment.
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }
}
