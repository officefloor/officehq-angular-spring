package net.officefloor.hq.app.role;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The roles a sheet's settings assign to user names. */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;

    public RoleService(RoleRepository roles, SheetService sheets, SheetRepository sheetStore, Audit audit) {
        this.roles = roles;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    /** The roles assigned on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<Role> list(String sheetId) {
        return roles.findBySheetIdOrderByUserName(sheetId);
    }

    /** The role of a user name on the sheet: the one assigned, else clerk. */
    @Transactional(readOnly = true)
    public String roleOf(String sheetId, String userName) {
        return roles.findBySheetIdAndUserName(sheetId, userName).map(Role::getRole).orElse(Role.CLERK);
    }

    /** Whether a user name is a viewer on the sheet: one who sees everything and can change nothing. */
    @Transactional(readOnly = true)
    public boolean isViewer(String sheetId, String userName) {
        return Role.VIEWER.equals(roleOf(sheetId, userName));
    }

    /** Assign a role (clerk, steward or viewer) to a user name, replacing the one it had. The sheet is locked
     *  first, so two assignments to one name are made one at a time. */
    @Transactional
    public Role set(String sheetId, String user, String userName, String role) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Role row = roles.findBySheetIdAndUserName(sheetId, userName)
                .orElseGet(() -> new Role(sheetId, userName, role));
        row.setRole(role);
        Role saved = roles.saveAndFlush(row);
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user
                + " user=" + saved.getUserName() + " role=" + saved.getRole());
        return saved;
    }
}
