package net.officefloor.hq.app.voidreason;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the void reasons of a sheet, each at most once, in the order they were added. While the list is not empty,
 * voiding a claim needs a reason chosen from it.
 */
@Service
public class VoidReasonService {

    private final VoidReasonRepository reasons;
    private final SheetRepository sheets;
    private final Audit audit;

    public VoidReasonService(VoidReasonRepository reasons, SheetRepository sheets, Audit audit) {
        this.reasons = reasons;
        this.sheets = sheets;
        this.audit = audit;
    }

    public List<VoidReason> list(String sheetId) {
        return reasons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add the reason (already trimmed, not blank) to the sheet's list; refused when it is already on it. */
    @Transactional
    public VoidReason add(String sheetId, String user, String reason) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        if (reasons.existsBySheetIdAndReason(sheetId, reason)) {
            throw new VoidReasonTakenException(sheetId, reason);
        }
        VoidReason saved;
        try {
            saved = reasons.saveAndFlush(new VoidReason(sheetId, reason));
        } catch (DataIntegrityViolationException e) {
            throw new VoidReasonTakenException(sheetId, reason);
        }
        audit.recordOnCommit("VOID_REASON_ADDED sheet=" + sheetId + " by=" + user + " reason=" + saved.getReason());
        return saved;
    }
}
