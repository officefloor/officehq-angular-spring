package net.officefloor.hq.app.voidreason;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The void reasons of a sheet. */
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

    /** The void reasons of the sheet in the order they were added. */
    @Transactional(readOnly = true)
    public List<VoidReason> list(String sheetId) {
        return reasons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a (trimmed) reason to the sheet's list; refused when blank or already on the list. */
    @Transactional
    public synchronized VoidReason add(String sheetId, String user, String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a void reason");
        }
        sheets.load(sheetId, user);
        if (reasons.existsBySheetIdAndReason(sheetId, text)) {
            throw alreadyListed();
        }
        VoidReason saved;
        try {
            saved = reasons.saveAndFlush(new VoidReason(sheetId, text, sheets.today(sheetId)));
        } catch (DataIntegrityViolationException e) {
            throw alreadyListed();
        }
        audit.recordOnCommit("VOID_REASON_ADDED sheet=" + sheetId + " by=" + user + " reason=" + text);
        return saved;
    }

    /** The reason a claim of the sheet is voided with: null when the sheet has no reasons (none is
     *  needed); refused when it has some and the reason given is not one of them. */
    @Transactional(readOnly = true)
    public String required(String sheetId, String reason) {
        if (!reasons.existsBySheetId(sheetId)) {
            return null;
        }
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty() || !reasons.existsBySheetIdAndReason(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a void reason from the sheet's list");
        }
        return text;
    }

    private static ResponseStatusException alreadyListed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That reason is already on the list");
    }
}
