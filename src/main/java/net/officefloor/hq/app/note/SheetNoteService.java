package net.officefloor.hq.app.note;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The notes on a sheet: texts users add, each kept with the sheet's today when it was added. */
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

    /** The sheet's notes, the newest first; under an as-of date only those added while the sheet's
     *  today was on or before it. */
    public List<SheetNote> list(String sheetId, LocalDate asOf) {
        return asOf == null ? notes.findBySheetIdOrderByIdDesc(sheetId)
                : notes.findBySheetIdAndAddedOnLessThanEqualOrderByIdDesc(sheetId, asOf);
    }

    /** Add a note to the sheet, dated the sheet's today. A blank text is refused. */
    @Transactional
    public SheetNote add(String sheetId, String user, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new NoteRefusedException("A note needs a text");
        }
        SheetNote saved = notes.saveAndFlush(new SheetNote(sheetId, trimmed, user, sheets.today(sheetId)));
        audit.recordOnCommit("NOTE_ADDED sheet=" + sheetId + " by=" + user + " text=" + trimmed);
        return saved;
    }
}
