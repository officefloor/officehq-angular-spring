package net.officefloor.hq.app.sheet;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
    }

    /** Load a sheet by id; an id never used before starts an empty sheet. */
    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user));
            } catch (DataIntegrityViolationException e) {
                // another user created it at the same moment
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }
}
