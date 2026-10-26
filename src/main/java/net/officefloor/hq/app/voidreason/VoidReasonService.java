package net.officefloor.hq.app.voidreason;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's list of void reasons. While the list is not empty, a claim is voided only with a reason chosen from
 * it.
 */
@Service
public class VoidReasonService {

    private final VoidReasonRepository reasons;
    private final SheetService sheets;
    private final Audit audit;

    public VoidReasonService(VoidReasonRepository reasons, SheetService sheets, Audit audit) {
        this.reasons = reasons;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's void reasons, in the order they were added. */
    @Transactional(readOnly = true)
    public List<VoidReason> list(String sheetId) {
        return reasons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Adds a reason, trimmed, to the sheet's list; refused when blank or already on the list. */
    @Transactional
    public VoidReason add(String by, String sheetId, String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Enter a void reason.");
        }
        sheets.lock(sheetId);
        if (reasons.existsBySheetIdAndReason(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, text + " is already a void reason.");
        }
        VoidReason added = reasons.saveAndFlush(new VoidReason(sheetId, text));
        audit.recordOnCommit("VOID_REASON_ADDED sheet=" + sheetId + " by=" + by + " reason=" + text);
        return added;
    }

    /**
     * The reason a claim is voided with: null when none is given and the list is empty. Refused when the list is not
     * empty and the reason is missing or not on it, or when a reason is given that is not on the list.
     */
    @Transactional(readOnly = true)
    public String refuseUnlessListed(String sheetId, String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            if (reasons.existsBySheetId(sheetId)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Choose a reason to void this claim.");
            }
            return null;
        }
        if (!reasons.existsBySheetIdAndReason(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, text + " is not a void reason.");
        }
        return text;
    }
}
