package net.officefloor.hq.app.voidreason;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The void reasons on a sheet: while its list is not empty, a claim is voided with one of them. */
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
    public List<VoidReason> list(String sheetId) {
        return reasons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Whether the reason is on the sheet's list. */
    public boolean listed(String sheetId, String reason) {
        return reasons.existsBySheetIdAndReason(sheetId, reason);
    }

    /** Add a reason, trimmed, to the sheet's list. Refused when it is blank or already on the list.
     *  The sheet is held locked so the same reason added at the same moment is checked against itself. */
    @Transactional
    public VoidReason add(String sheetId, String user, String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new VoidReasonRefusedException("A void reason cannot be blank");
        }
        sheets.lock(sheetId);
        if (reasons.existsBySheetIdAndReason(sheetId, text)) {
            throw new VoidReasonRefusedException("The reason " + text + " is already on the sheet's list");
        }
        VoidReason saved = reasons.saveAndFlush(new VoidReason(sheetId, text));
        audit.recordOnCommit("VOID_REASON_ADDED sheet=" + sheetId + " by=" + user + " reason=" + text);
        return saved;
    }
}
