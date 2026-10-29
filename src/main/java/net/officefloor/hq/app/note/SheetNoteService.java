package net.officefloor.hq.app.note;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.asof.AsOfContext;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The sheet's notes, each with who added it and the sheet's today then. */
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

    /**
     * The sheet's notes, newest first. As of a date (which stands in for the sheet's today), only those added while
     * the sheet's today was on or before it.
     */
    @Transactional(readOnly = true)
    public List<SheetNote> list(String sheetId) {
        if (AsOfContext.active()) {
            return notes.findBySheetIdAndAddedOnLessThanEqualOrderByIdDesc(sheetId, sheets.load(sheetId).getToday());
        }
        return notes.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Adds a note, trimmed, with the sheet's today; refused when blank. */
    @Transactional
    public SheetNote add(String by, String sheetId, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Enter a note.");
        }
        Sheet sheet = sheets.lock(sheetId);
        SheetNote added = notes.saveAndFlush(new SheetNote(sheetId, sheet.getToday(), by, trimmed));
        audit.recordOnCommit("NOTE_ADDED sheet=" + sheetId + " by=" + by + " text=" + trimmed);
        return added;
    }
}
