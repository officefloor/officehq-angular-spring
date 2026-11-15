package net.officefloor.hq.app.note;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** The sheet's notes, newest first; with a date, only those added while the sheet's today was
     *  on or before it. */
    @Transactional(readOnly = true)
    public List<SheetNote> list(String sheetId, LocalDate asOf) {
        return asOf == null
                ? notes.findBySheetIdOrderByIdDesc(sheetId)
                : notes.findBySheetIdAndAddedOnLessThanEqualOrderByIdDesc(sheetId, asOf);
    }

    /** Add a note (text not blank, trimmed) to the sheet, dated the sheet's today; the sheet is
     *  created first if it was never loaded. */
    @Transactional
    public SheetNote add(String sheetId, String user, String text) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        SheetNote note = notes.saveAndFlush(new SheetNote(sheetId, sheet.getToday(), user, text));
        audit.recordOnCommit("NOTE_ADDED sheet=" + sheetId + " by=" + user + " text=" + text);
        return note;
    }
}
