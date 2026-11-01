package net.officefloor.hq.app.voidreason;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet's list of void reasons, each at most once. While the list is not empty, a claim is voided
 * only with a reason chosen from it.
 */
@Service
public class VoidReasonService {

    private final SheetRepository sheets;
    private final VoidReasonRepository reasons;
    private final Audit audit;

    public VoidReasonService(SheetRepository sheets, VoidReasonRepository reasons, Audit audit) {
        this.sheets = sheets;
        this.reasons = reasons;
        this.audit = audit;
    }

    /** The sheet's void reasons, in the order they were added. */
    @Transactional(readOnly = true)
    public List<VoidReason> reasons(String sheetId) {
        return reasons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a reason, trimmed with its inner spaces collapsed; refused when blank or already on the list. */
    @Transactional
    public VoidReason add(String sheetId, String user, String reason) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        String text = textOf(reason);
        if (text == null) throw new VoidReasonBlankException();
        if (reasons.existsBySheetIdAndReason(sheetId, text)) throw new DuplicateVoidReasonException();
        VoidReason saved = reasons.saveAndFlush(new VoidReason(sheetId, text));
        audit.recordOnCommit("VOID_REASON_ADDED sheet=" + sheetId + " by=" + user.strip() + " reason=" + text);
        return saved;
    }

    /**
     * The reason a claim is voided with: none while the sheet's list is empty, else the given one,
     * which must be on the list.
     */
    @Transactional(readOnly = true)
    public String reasonForVoid(String sheetId, String reason) {
        if (!reasons.existsBySheetId(sheetId)) return null;
        String text = textOf(reason);
        if (text == null || !reasons.existsBySheetIdAndReason(sheetId, text)) throw new VoidReasonRequiredException();
        return text;
    }

    /** The reason trimmed, inner spaces collapsed; null when blank. */
    private static String textOf(String reason) {
        if (reason == null) return null;
        String text = reason.strip().replaceAll("\\s+", " ");
        return text.isEmpty() ? null : text;
    }
}
