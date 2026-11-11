package net.officefloor.hq.app.sheet;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Loads a sheet by id, starting an empty one the first time the id is used. */
@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
    }

    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> start(id, user));
    }

    private Sheet start(String id, String user) {
        try {
            return sheets.saveAndFlush(new Sheet(id, user));
        } catch (DataIntegrityViolationException e) {
            // Another user started the same sheet at the same moment: it exists now.
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }
}
