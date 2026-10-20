package net.officefloor.hq.app.sheet;

import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Archiving a sheet, and whether a sheet is archived. */
@Service
public class ArchiveService {

    private final SheetRepository sheets;
    private final SheetService sheetService;
    private final SettingService settings;
    private final Audit audit;

    public ArchiveService(SheetRepository sheets, SheetService sheetService, SettingService settings, Audit audit) {
        this.sheets = sheets;
        this.sheetService = sheetService;
        this.settings = settings;
        this.audit = audit;
    }

    /** Archive the sheet: only a steward may, and only once. */
    @Transactional
    public Sheet archive(String user, String id) {
        sheetService.lock(id);
        Sheet sheet = sheets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
        settings.requireSteward(id, user);
        requireNotArchived(sheet);
        sheet.archive();
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + id + " by=" + user);
        return sheet;
    }

    /** Refuse any change to an archived sheet; a sheet not in the store is not archived. */
    @Transactional(readOnly = true)
    public void requireNotArchived(String id) {
        if (sheets.findById(id).map(Sheet::isArchived).orElse(false)) {
            throw archived();
        }
    }

    private static void requireNotArchived(Sheet sheet) {
        if (sheet.isArchived()) {
            throw archived();
        }
    }

    private static ResponseStatusException archived() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "The sheet is archived.");
    }
}
