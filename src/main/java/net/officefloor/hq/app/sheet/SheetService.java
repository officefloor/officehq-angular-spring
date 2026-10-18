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

    /** The sheet with this id; a sheet never loaded before is started empty. */
    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now()));
            } catch (DataIntegrityViolationException raced) {
                return sheets.findById(id).orElseThrow(() -> raced);
            }
        });
    }
}
