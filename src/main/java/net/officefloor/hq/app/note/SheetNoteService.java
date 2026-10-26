package net.officefloor.hq.app.note;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SheetNoteService {

    private final SheetNoteRepository notes;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;

    public SheetNoteService(SheetNoteRepository notes, SheetService sheets, SheetRepository sheetRows, Audit audit) {
        this.notes = notes;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    /** The sheet's notes, newest first. Under an as-of date the store is the sheet as it stood then,
     *  so only the notes added while its today was on or before that date are listed. */
    @Transactional(readOnly = true)
    public List<SheetNote> list(String sheetId) {
        return notes.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a note (trimmed) against the sheet's today, refused when it is blank. */
    @Transactional
    public SheetNote add(String sheetId, String user, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Type a note.");
        }
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        SheetNote saved = notes.saveAndFlush(new SheetNote(sheetId, sheets.today(sheetId), user, trimmed));
        audit.recordOnCommit("NOTE_ADDED sheet=" + sheetId + " by=" + user + " text=" + saved.getText());
        return saved;
    }
}
