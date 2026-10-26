package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A sheet's settings: the role each user name has, and the clerk limit. */
@Service
public class SettingsService {

    private final SheetRoleRepository roles;
    private final SheetRepository sheetRepository;
    private final SheetService sheets;
    private final Audit audit;

    public SettingsService(SheetRoleRepository roles, SheetRepository sheetRepository, SheetService sheets,
            Audit audit) {
        this.roles = roles;
        this.sheetRepository = sheetRepository;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The roles set on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserAsc(sheetId);
    }

    /** The user's role on the sheet: clerk unless set otherwise. */
    @Transactional(readOnly = true)
    public String roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(SheetRole.CLERK);
    }

    /** Sets the user's role on the sheet; refused unless it is clerk or steward. */
    @Transactional
    public SheetRole setRole(String by, String sheetId, String user, String role) {
        if (!SheetRole.CLERK.equals(role) && !SheetRole.STEWARD.equals(role)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A role is clerk or steward.");
        }
        sheets.lock(sheetId);
        SheetRole r = roles.findBySheetIdAndUser(sheetId, user).orElseGet(() -> new SheetRole(sheetId, user, role));
        r.setRole(role);
        SheetRole saved;
        try {
            saved = roles.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That role was just set by someone else.");
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + by + " user=" + user + " role=" + role);
        return saved;
    }

    /** Sets the sheet's clerk limit; null means no limit. */
    @Transactional
    public Sheet setClerkLimit(String by, String sheetId, BigDecimal limit) {
        Sheet sheet = sheets.lock(sheetId);
        BigDecimal scaled = limit == null ? null : limit.setScale(2, RoundingMode.UNNECESSARY);
        sheet.setClerkLimit(scaled);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=clerk-limit value="
                + (scaled == null ? "" : scaled.toPlainString()));
        return saved;
    }

    /**
     * Refuses the user settling a claim with the due (null for n/a) when they are a clerk and the due is above the
     * sheet's clerk limit. A steward settles anything, and an n/a due is within any limit.
     */
    @Transactional(readOnly = true)
    public void refuseAboveClerkLimit(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = sheets.load(sheetId).getClerkLimit();
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return;
        }
        if (SheetRole.STEWARD.equals(roleOf(sheetId, user))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "A clerk cannot settle a claim whose due is above the clerk limit.");
    }
}
