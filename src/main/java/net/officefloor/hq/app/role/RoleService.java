package net.officefloor.hq.app.role;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The roles user names have on a sheet: clerk (the default for any name) or steward. */
@Service
public class RoleService {

    private final SheetRoleRepository roles;
    private final SheetService sheets;
    private final Audit audit;

    public RoleService(SheetRoleRepository roles, SheetService sheets, Audit audit) {
        this.roles = roles;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The roles given on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<SheetRole> list(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** The role of a user name on the sheet: clerk unless it was given another. */
    @Transactional(readOnly = true)
    public String roleOf(String sheetId, String userName) {
        return roles.findBySheetIdAndUserName(sheetId, userName).map(SheetRole::getRole).orElse(SheetRole.CLERK);
    }

    /** Whether a user name's role on the sheet may take an action of the permission matrix (open,
     *  settle, void, reassign, close, reopen). */
    @Transactional(readOnly = true)
    public boolean may(String sheetId, String userName, String action) {
        return sheets.rolesAllowed(sheetId, action).contains(roleOf(sheetId, userName));
    }

    /** Give a user name a role on the sheet, replacing the one it had. */
    @Transactional
    public synchronized SheetRole set(String sheetId, String user, String userName, String role) {
        sheets.load(sheetId, user);
        SheetRole given = roles.findBySheetIdAndUserName(sheetId, userName)
                .orElseGet(() -> new SheetRole(sheetId, userName, role));
        given.setRole(role);
        SheetRole saved = roles.saveAndFlush(given);
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + userName + " role=" + role);
        return saved;
    }
}
