package net.officefloor.hq.app.role;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The roles user names have on a sheet: listed, set and looked up. */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final SheetService sheets;
    private final Audit audit;

    public RoleService(RoleRepository roles, SheetService sheets, Audit audit) {
        this.roles = roles;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The roles set on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<Role> list(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** The role a user name has on the sheet: the one set for it, else clerk. */
    @Transactional(readOnly = true)
    public String roleOf(String sheetId, String userName) {
        return roles.findBySheetIdAndUserName(sheetId, userName).map(Role::getRole).orElse(Role.CLERK);
    }

    /** Set the role of a user name on the sheet, replacing any it had. */
    @Transactional
    public Role set(String sheetId, String user, String userName, String role) {
        sheets.lock(sheetId);
        Role saved = roles.findBySheetIdAndUserName(sheetId, userName)
                .orElseGet(() -> new Role(sheetId, userName, role));
        saved.setRole(role);
        saved = roles.saveAndFlush(saved);
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user
                + " user=" + saved.getUserName() + " role=" + saved.getRole());
        return saved;
    }
}
