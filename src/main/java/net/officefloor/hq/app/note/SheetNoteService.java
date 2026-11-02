package net.officefloor.hq.app.note;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's notes, newest first, each dated with the sheet's today when it was added. As of a date
 * the sheet shows the notes it held at the end of that date, as it does all its rows.
 */
@Service
public class SheetNoteService {

    private final SheetRepository sheets;
    private final SheetNoteRepository notes;
    private final Audit audit;

    public SheetNoteService(SheetRepository sheets, SheetNoteRepository notes, Audit audit) {
        this.sheets = sheets;
        this.notes = notes;
        this.audit = audit;
    }

    /** The sheet's notes, newest first. */
    @Transactional(readOnly = true)
    public List<SheetNote> notes(String sheetId) {
        return notes.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a note of the (trimmed) text, dated the sheet's today; a blank text is refused. */
    @Transactional
    public SheetNote add(String sheetId, String user, String text) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) throw new NoteBlankException();
        String by = user.strip();
        SheetNote saved = notes.saveAndFlush(new SheetNote(sheetId, sheet.getToday(), by, value));
        audit.recordOnCommit("NOTE_ADDED sheet=" + sheetId + " by=" + by + " text=" + value);
        return saved;
    }
}
