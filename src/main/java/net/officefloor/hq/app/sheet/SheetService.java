package net.officefloor.hq.app.sheet;

import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
    }

    /** The sheet with this id, as the store holds it now; a sheet never loaded before starts empty. */
    @Transactional
    public Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> create(id));
    }

    private Sheet create(String id) {
        try {
            return sheets.saveAndFlush(new Sheet(id, Instant.now()));
        } catch (DataIntegrityViolationException raced) {
            // Another user loaded the same new id at the same moment.
            return sheets.findById(id).orElseThrow(() -> raced);
        }
    }
}
