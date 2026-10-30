package net.officefloor.hq.app.note;

import java.util.List;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The notes on a sheet, newest first: each a non-blank text, who added it and the sheet's today then. */
@Service
public class SheetNotes {

    /** The longest a note may be. */
    static final int TEXT_MAX = 1000;

    private final SheetNoteRepository notes;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;

    public SheetNotes(SheetNoteRepository notes, SheetService sheets, SheetRepository sheetRows,
            ApplicationEventPublisher events) {
        this.notes = notes;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.events = events;
    }

    /** The notes on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<SheetNote> list(String sheetId) {
        return notes.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a note, its text trimmed, dated the sheet's today; refused when blank or too long. */
    @Transactional
    public void add(String sheetId, String user, String given) {
        String text = given == null ? "" : given.strip();
        if (text.isEmpty()) {
            throw new NoteRefused("Enter the note's text.");
        }
        if (text.length() > TEXT_MAX) {
            throw new NoteRefused("The note is too long.");
        }
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        notes.saveAndFlush(new SheetNote(sheetId, text, user, sheets.today(sheetId)));
        events.publishEvent(new NoteAdded(sheetId, user, text));
    }
}
