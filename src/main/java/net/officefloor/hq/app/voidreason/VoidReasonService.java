package net.officefloor.hq.app.voidreason;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's void reasons: texts added on the screen, each at most once. While the sheet has any,
 * a claim is voided only with one of them (see {@link #refuseUnlessListed}).
 */
@Service
public class VoidReasonService {

    private final VoidReasonRepository reasons;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;
    private final RoleService roles;

    public VoidReasonService(VoidReasonRepository reasons, SheetService sheets, SheetRepository sheetRows, Audit audit,
            RoleService roles) {
        this.reasons = reasons;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
        this.roles = roles;
    }

    @Transactional(readOnly = true)
    public List<VoidReason> list(String sheetId) {
        return reasons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a void reason (trimmed); a blank one is refused (422), one already on the list (409). */
    @Transactional
    public VoidReason add(String sheetId, String user, String reason) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Enter a void reason.");
        }
        // hold the sheet so the same reason added twice at the same moment is checked one after the other
        sheetRows.findLockedById(sheetId);
        if (reasons.existsBySheetIdAndReason(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That reason is already on the list.");
        }
        VoidReason added;
        try {
            added = reasons.saveAndFlush(new VoidReason(sheetId, text));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That reason is already on the list.");
        }
        audit.recordOnCommit("VOID_REASON_ADDED sheet=" + sheetId + " by=" + user + " reason=" + added.getReason());
        return added;
    }

    /**
     * The reason to void a claim with, trimmed (none when not given): refused (422) when the sheet
     * has void reasons and none of them is given, or when one is given that is not on the list.
     */
    // a refusal leaves the caller's transaction usable (a bulk void refuses one claim and goes on)
    @Transactional(readOnly = true, noRollbackFor = ResponseStatusException.class)
    public String refuseUnlessListed(String sheetId, String reason) {
        String text = reason == null || reason.isBlank() ? null : reason.trim();
        if (text == null) {
            if (reasons.existsBySheetId(sheetId)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Choose a reason to void this claim.");
            }
            return null;
        }
        if (!reasons.existsBySheetIdAndReason(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "That void reason is not on the list.");
        }
        return text;
    }
}
