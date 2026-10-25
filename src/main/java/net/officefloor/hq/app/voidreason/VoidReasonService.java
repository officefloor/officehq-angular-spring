package net.officefloor.hq.app.voidreason;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VoidReasonService {

    private final VoidReasonRepository reasons;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;

    public VoidReasonService(VoidReasonRepository reasons, SheetService sheets, SheetRepository sheetRows,
            Audit audit) {
        this.reasons = reasons;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    /** The sheet's void reasons in the order they were added. */
    @Transactional(readOnly = true)
    public List<VoidReason> list(String sheetId) {
        return reasons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a reason (trimmed) to the sheet's list, refused when it is blank or already on the list. */
    @Transactional
    public VoidReason add(String sheetId, String user, String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Type a void reason.");
        }
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        if (reasons.existsBySheetIdAndReason(sheetId, text)) throw alreadyListed(text);
        VoidReason saved;
        try {
            saved = reasons.saveAndFlush(new VoidReason(sheetId, text));
        } catch (DataIntegrityViolationException raced) {
            throw alreadyListed(text);
        }
        audit.recordOnCommit("VOID_REASON_ADDED sheet=" + sheetId + " by=" + user + " reason=" + saved.getReason());
        return saved;
    }

    /** The reason a claim of the sheet is voided with: none while the sheet's list is empty, else the
     *  given reason (trimmed), refused when it is blank or not on the list. */
    @Transactional(readOnly = true)
    public String reasonToVoid(String sheetId, String reason) {
        if (!reasons.existsBySheetId(sheetId)) return null;
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Choose a void reason to void a claim on this sheet.");
        }
        if (!reasons.existsBySheetIdAndReason(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "\"" + text + "\" is not on the sheet's list of void reasons.");
        }
        return text;
    }

    private static ResponseStatusException alreadyListed(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "\"" + reason + "\" is already a void reason.");
    }
}
