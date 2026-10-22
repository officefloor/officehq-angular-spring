package net.officefloor.hq.app.voidreason;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The void reasons on a sheet: listed, added, and the check a void's reason is one of them. */
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

    /** The sheet's void reasons in the order they were added. */
    @Transactional(readOnly = true)
    public List<VoidReason> list(String sheetId) {
        return reasons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a void reason (trimmed) to the sheet; refused when blank or already on its list. */
    @Transactional
    public VoidReason add(String sheetId, String user, String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A void reason cannot be blank.");
        }
        // Take the sheet's lock first, so the same reason added twice at once cannot both pass the check.
        sheets.lock(sheetId);
        if (reasons.existsBySheetIdAndReason(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That reason is already on this sheet's list.");
        }
        VoidReason saved = reasons.saveAndFlush(new VoidReason(sheetId, text));
        audit.recordOnCommit("VOID_REASON_ADDED sheet=" + sheetId + " by=" + user + " reason=" + text);
        return saved;
    }

    /**
     * The reason a claim is voided with: none while the sheet's list is empty and none is given; refused
     * when the list is not empty and none is given, or when the one given is not on the list.
     */
    @Transactional(readOnly = true)
    public String require(String sheetId, String reason) {
        String text = reason == null ? "" : reason.trim();
        List<String> listed = list(sheetId).stream().map(VoidReason::getReason).toList();
        if (text.isEmpty()) {
            if (!listed.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Choose a reason from the list to void the claim.");
            }
            return null;
        }
        if (!listed.contains(text)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The reason is not on this sheet's list.");
        }
        return text;
    }
}
