package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The sheet's settings (its clerk limit) and the role each user name holds on it. */
@Service
public class SettingsService {

    /** The key the clerk limit is audited under. */
    public static final String CLERK_LIMIT = "clerk-limit";

    private final SheetRoleRepository roles;
    private final SheetService sheets;
    private final Audit audit;

    public SettingsService(SheetRoleRepository roles, SheetService sheets, Audit audit) {
        this.roles = roles;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's clerk limit; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getClerkLimit).orElse(null);
    }

    /** Set the sheet's clerk limit (null: no limit), the sheet created first if it was never loaded. */
    @Transactional
    public BigDecimal setClerkLimit(String sheetId, String user, BigDecimal limit) {
        Sheet sheet = sheets.load(sheetId, user);
        BigDecimal scaled = limit == null ? null : limit.setScale(2);
        sheet.setClerkLimit(scaled);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + CLERK_LIMIT + " value=" + (scaled == null ? "" : scaled.toPlainString()));
        return scaled;
    }

    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserAsc(sheetId);
    }

    /** The role a user name holds on the sheet: a clerk unless set otherwise. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** Give a user name a role on the sheet, the sheet created first if it was never loaded. */
    @Transactional
    public SheetRole setRole(String sheetId, String user, String name, Role role) {
        sheets.load(sheetId, user);
        SheetRole held = roles.findBySheetIdAndUser(sheetId, name).orElse(null);
        try {
            if (held == null) {
                held = roles.saveAndFlush(new SheetRole(sheetId, name, role));
            } else {
                held.setRole(role);
                roles.flush();
            }
        } catch (DataIntegrityViolationException e) {
            // set by another user at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Role changed at the same moment");
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + name + " role=" + role.key());
        return held;
    }

    /** Whether a user may settle a claim showing a due (null: n/a, within any limit): a steward
     *  settles anything, a clerk only a due at or below the sheet's clerk limit, if it has one. */
    @Transactional(readOnly = true)
    public boolean maySettle(String sheetId, String user, BigDecimal due) {
        if (due == null || roleOf(sheetId, user) == Role.STEWARD) {
            return true;
        }
        BigDecimal limit = clerkLimit(sheetId);
        return limit == null || due.compareTo(limit) <= 0;
    }
}
