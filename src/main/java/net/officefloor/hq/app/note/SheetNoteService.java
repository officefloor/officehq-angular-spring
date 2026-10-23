package net.officefloor.hq.app.note;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The notes on a sheet: listed newest first, and added dated with the sheet's today. */
@Service
public class SheetNoteService {

    private final SheetNoteRepository notes;
    private final SheetService sheets;
    private final Audit audit;

    public SheetNoteService(SheetNoteRepository notes, SheetService sheets, Audit audit) {
        this.notes = notes;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's notes, newest first. */
    @Transactional(readOnly = true)
    public List<SheetNote> list(String sheetId) {
        return notes.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a note to the sheet, dated with the sheet's today; a blank note is refused. */
    @Transactional
    public SheetNote add(String sheetId, String user, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A note needs some text.");
        }
        sheets.lock(sheetId);
        SheetNote saved = notes.save(new SheetNote(sheetId, sheets.today(sheetId), user, trimmed));
        audit.recordOnCommit("NOTE_ADDED sheet=" + sheetId + " by=" + user + " text=" + trimmed);
        return saved;
    }
}
