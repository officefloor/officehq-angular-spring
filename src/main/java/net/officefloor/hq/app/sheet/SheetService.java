package net.officefloor.hq.app.sheet;

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

    private Sheet create(String id) {
        try {
            return sheets.saveAndFlush(new Sheet(id));
        } catch (DataIntegrityViolationException e) {
            // another user created it at the same moment
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }
}
