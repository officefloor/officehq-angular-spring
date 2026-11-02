package net.officefloor.hq.app.sheet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet that was never loaded before starts empty. */
@Service
public class SheetService {

    private final SheetRepository sheets;

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
    }

    /** The sheet with this id, created empty the first time it is loaded. */
    @Transactional
    public synchronized Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> sheets.save(new Sheet(id, user)));
    }
}
