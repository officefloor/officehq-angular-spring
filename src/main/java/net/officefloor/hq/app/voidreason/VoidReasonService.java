package net.officefloor.hq.app.voidreason;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The void reasons on a sheet. While the list is not empty, a void of a claim on the sheet names one
 * of them (see {@link #refuseUnlisted}). */
@Service
public class VoidReasonService {

    private final SheetRepository sheets;
    private final SheetVoidReasonRepository reasons;
    private final Audit audit;

    public VoidReasonService(SheetRepository sheets, SheetVoidReasonRepository reasons, Audit audit) {
        this.sheets = sheets;
        this.reasons = reasons;
        this.audit = audit;
    }

    /** The sheet's void reasons, in the order they were added. */
    @Transactional(readOnly = true)
    public List<SheetVoidReason> reasons(String sheetId) {
        return reasons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add {@code reason} (trimmed, not blank); refused when it is already on the sheet's list. */
    @Transactional
    public SheetVoidReason add(String sheetId, String by, String reason) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (reasons.existsBySheetIdAndReason(sheetId, reason)) throw new VoidReasonTakenException();
        SheetVoidReason added = reasons.saveAndFlush(new SheetVoidReason(sheetId, reason));
        audit.change("VOID_REASON_ADDED", sheetId, by, "reason=" + reason);
        return added;
    }

    /** The reason a void on the sheet is given: null when the sheet's list is empty; refused when the
     * list is not empty and {@code reason} is blank or not on it. */
    @Transactional(readOnly = true)
    public String refuseUnlisted(String sheetId, String reason) {
        if (!reasons.existsBySheetId(sheetId)) return null;
        String given = reason == null ? "" : reason.trim();
        if (given.isEmpty() || !reasons.existsBySheetIdAndReason(sheetId, given)) throw new VoidReasonRequiredException();
        return given;
    }
}
