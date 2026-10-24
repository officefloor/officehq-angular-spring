package net.officefloor.hq.app.note;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The notes on a sheet, each kept with who added it and the sheet's today when it was added. */
@Service
public class NoteService {

    private final SheetRepository sheets;
    private final SheetNoteRepository notes;
    private final Audit audit;

    public NoteService(SheetRepository sheets, SheetNoteRepository notes, Audit audit) {
        this.sheets = sheets;
        this.notes = notes;
        this.audit = audit;
    }

    /** The sheet's notes, newest first; with {@code asOf}, only those added while the sheet's today was on
     * or before it. */
    @Transactional(readOnly = true)
    public List<SheetNote> notes(String sheetId, LocalDate asOf) {
        return notes.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(n -> asOf == null || !n.getAddedOn().isAfter(asOf)).toList();
    }

    /** Add a note with {@code text} (trimmed, not blank), dated the sheet's today. */
    @Transactional
    public SheetNote add(String sheetId, String by, String text) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        String noted = text == null ? "" : text.trim();
        if (noted.isEmpty()) throw new NoteBlankException();
        SheetNote added = notes.saveAndFlush(new SheetNote(sheetId, sheet.getToday(), by, noted));
        audit.change("NOTE_ADDED", sheetId, by, "text=" + noted);
        return added;
    }
}
