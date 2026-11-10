package net.officefloor.hq.app.note;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.AsOfFilter;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's notes: a text (trimmed) added by a user, dated by the sheet's today then. Listed newest
 * first; shown as of a date, only those added while the sheet's today was on or before that date.
 */
@Service
public class SheetNoteService {

    private final SheetNoteRepository notes;
    private final SheetService sheets;
    private final RoleService roles;
    private final Audit audit;

    public SheetNoteService(SheetNoteRepository notes, SheetService sheets, RoleService roles, Audit audit) {
        this.notes = notes;
        this.sheets = sheets;
        this.roles = roles;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SheetNote> list(String sheetId) {
        return AsOfFilter.current()
                .map(date -> notes.findBySheetIdAndAddedOnLessThanEqualOrderByIdDesc(sheetId, date))
                .orElseGet(() -> notes.findBySheetIdOrderByIdDesc(sheetId));
    }

    /** Add a note (trimmed); a blank one is refused (422), as is any by a viewer (403) or on an archived sheet (423). */
    @Transactional
    public SheetNote add(String sheetId, String user, String text) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Enter the note's text.");
        }
        SheetNote saved = notes.saveAndFlush(new SheetNote(sheetId, trimmed, user, sheets.today(sheetId)));
        audit.recordOnCommit("NOTE_ADDED sheet=" + sheetId + " by=" + user + " text=" + saved.getText());
        return saved;
    }
}
