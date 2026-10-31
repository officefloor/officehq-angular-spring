package net.officefloor.hq.app.note;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The notes on a sheet. */
@Service
public class NoteService {

    private final NoteRepository notes;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;

    public NoteService(NoteRepository notes, SheetService sheets, SheetRepository sheetStore, Audit audit) {
        this.notes = notes;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    /** The sheet's notes, newest first; with a date, only those added while the sheet's today was on or before it. */
    @Transactional(readOnly = true)
    public List<Note> list(String sheetId, LocalDate asOf) {
        return asOf == null
                ? notes.findBySheetIdOrderByIdDesc(sheetId)
                : notes.findBySheetIdAndAddedOnLessThanEqualOrderByIdDesc(sheetId, asOf);
    }

    /** Add a note, trimmed, to the sheet under its today; refused when blank. */
    @Transactional
    public Note add(String sheetId, String user, String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A note is not blank");
        }
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Note saved = notes.saveAndFlush(new Note(sheetId, trimmed, user, sheet.getToday()));
        audit.recordChangeOnCommit(sheetId, user, "NOTE_ADDED sheet=" + sheetId + " by=" + user
                + " text=" + saved.getText());
        return saved;
    }
}
