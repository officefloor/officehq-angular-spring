package net.officefloor.hq.app.note;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The notes of a sheet. */
@Service
public class NoteService {

    private final NoteRepository notes;
    private final SheetService sheets;
    private final Audit audit;

    public NoteService(NoteRepository notes, SheetService sheets, Audit audit) {
        this.notes = notes;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The notes of the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Note> list(String sheetId) {
        return notes.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a note (trimmed) to the sheet, dated the sheet's today; refused when it is blank. */
    @Transactional
    public Note add(String sheetId, String user, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the note's text");
        }
        sheets.load(sheetId, user);
        Note saved = notes.saveAndFlush(new Note(sheetId, trimmed, user, sheets.today(sheetId)));
        audit.recordOnCommit("NOTE_ADDED sheet=" + sheetId + " by=" + user + " text=" + trimmed);
        return saved;
    }
}
