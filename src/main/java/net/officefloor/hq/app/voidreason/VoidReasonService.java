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

/** The void reasons on a sheet. */
@Service
public class VoidReasonService {

    private final VoidReasonRepository reasons;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;

    public VoidReasonService(VoidReasonRepository reasons, SheetService sheets, SheetRepository sheetStore,
            Audit audit) {
        this.reasons = reasons;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    /** The sheet's void reasons, in the order they were added. */
    @Transactional(readOnly = true)
    public List<VoidReason> list(String sheetId) {
        return reasons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a reason, trimmed, to the sheet's list; refused when blank or already on it. */
    @Transactional
    public VoidReason add(String sheetId, String user, String reason) {
        String text = normalise(reason);
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A void reason is not blank");
        }
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        if (reasons.existsBySheetIdAndReason(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That reason is already on the list");
        }
        VoidReason saved;
        try {
            saved = reasons.saveAndFlush(new VoidReason(sheetId, text));
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That reason is already on the list");
        }
        audit.recordChangeOnCommit(sheetId, user, "VOID_REASON_ADDED sheet=" + sheetId + " by=" + user
                + " reason=" + saved.getReason());
        return saved;
    }

    /** The reason a claim on the sheet is voided with: {@code reason} trimmed (none when null or blank). Refused
     *  when the sheet's list is not empty and it is not on it, or when one is given that is not on it. */
    @Transactional(readOnly = true)
    public String refuseUnlisted(String sheetId, String reason) {
        String text = normalise(reason);
        if (text == null) {
            if (reasons.existsBySheetId(sheetId)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Choose a reason from the sheet's list to void a claim");
            }
            return null;
        }
        if (!reasons.existsBySheetIdAndReason(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "That reason is not on the sheet's list");
        }
        return text;
    }

    /** A reason trimmed with its inner runs of white space made single spaces; none when null or blank. */
    private static String normalise(String reason) {
        if (reason == null) {
            return null;
        }
        String text = reason.strip().replaceAll("\\s+", " ");
        return text.isEmpty() ? null : text;
    }
}
