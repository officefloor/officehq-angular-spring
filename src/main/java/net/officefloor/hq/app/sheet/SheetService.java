package net.officefloor.hq.app.sheet;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Loads sheets by id; a sheet never loaded before starts empty. */
@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
    }

    /** The sheet with this id, created empty when it was never loaded before. */
    public Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id));
            } catch (DataIntegrityViolationException e) {
                return sheets.findById(id).orElseThrow(() -> e); // created concurrently
            }
        });
    }
}
