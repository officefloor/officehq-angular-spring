package net.officefloor.hq.app.sheet;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Loads a sheet by id, starting an empty one when the id was never used. */
@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
    }

    public Sheet load(String id, String user) {
        try {
            return loadOrCreate(id, user);
        } catch (DataIntegrityViolationException e) {
            // Another user started the same sheet at the same moment: theirs is the one.
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }

    private Sheet loadOrCreate(String id, String user) {
        return sheets.findById(id).orElseGet(() -> sheets.saveAndFlush(new Sheet(id, user)));
    }
}
