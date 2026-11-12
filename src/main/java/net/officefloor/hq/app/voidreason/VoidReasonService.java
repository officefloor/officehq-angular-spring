package net.officefloor.hq.app.voidreason;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's list of void reasons. */
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

    /** Add a reason (surrounding spaces removed) to the sheet's list; a blank one, or one already on the
     *  list, is refused. */
    @Transactional
    public VoidReason add(String sheetId, String user, String reason) {
        sheets.today(sheetId);
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) {
            throw new VoidReasonRefusedException("Enter a void reason.");
        }
        if (reasons.existsBySheetIdAndReason(sheetId, r)) {
            throw new VoidReasonRefusedException("\"" + r + "\" is already a void reason.");
        }
        VoidReason saved = reasons.saveAndFlush(new VoidReason(sheetId, r));
        audit.recordOnCommit("VOID_REASON_ADDED sheet=" + sheetId + " by=" + user + " reason=" + r);
        return saved;
    }
}
