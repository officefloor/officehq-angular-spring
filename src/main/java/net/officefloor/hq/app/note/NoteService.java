package net.officefloor.hq.app.note;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the notes of a sheet, newest first. Each is dated with the sheet's today when it was added, so the as-of view
 * shows the notes added while the sheet's today was on or before its date.
 */
@Service
public class NoteService {

    private final NoteRepository notes;
    private final SheetRepository sheets;
    private final Audit audit;

    public NoteService(NoteRepository notes, SheetRepository sheets, Audit audit) {
        this.notes = notes;
        this.sheets = sheets;
        this.audit = audit;
    }

    public List<Note> list(String sheetId) {
        return notes.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add the note (its text already trimmed, not blank) under the sheet's today. */
    @Transactional
    public Note add(String sheetId, String user, String text) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(() -> new SheetNotFoundException(sheetId));
        Note saved = notes.saveAndFlush(new Note(sheetId, sheet.getToday(), user, text));
        audit.recordOnCommit("NOTE_ADDED sheet=" + sheetId + " by=" + user + " text=" + saved.getText());
        return saved;
    }
}
