package net.officefloor.hq.app.note;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's notes. */
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

    /** The sheet's notes, newest first; only those added while its today was on or before the as-of date
     *  when one is given. */
    @Transactional(readOnly = true)
    public List<SheetNote> list(String sheetId, LocalDate asOf) {
        sheets.today(sheetId);
        return asOf == null ? notes.findBySheetIdOrderByIdDesc(sheetId)
                : notes.findBySheetIdAndAddedOnLessThanEqualOrderByIdDesc(sheetId, asOf);
    }

    /** Add a note, dated by the sheet's today; a blank text is refused. */
    @Transactional
    public SheetNote add(String sheetId, String user, String text) {
        LocalDate today = sheets.today(sheetId);
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            throw new NoteRefusedException("Enter the note's text.");
        }
        SheetNote saved = notes.saveAndFlush(new SheetNote(sheetId, today, user, t));
        audit.recordOnCommit("NOTE_ADDED sheet=" + sheetId + " by=" + user + " text=" + t);
        return saved;
    }
}
