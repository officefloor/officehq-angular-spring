package net.officefloor.hq.app.role;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The roles given to user names on a sheet; a name never given one is a clerk. */
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

    @Transactional(readOnly = true)
    public List<SheetRole> list(String sheetId) {
        return roles.findBySheetIdOrderByUserAsc(sheetId);
    }

    /** The user's role on the sheet: clerk unless given another. */
    @Transactional(readOnly = true)
    public Role of(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** Give a user name a role, recorded as ROLE_SET user=&lt;name&gt; role=&lt;role&gt;. */
    @Transactional
    public SheetRole set(String sheetId, String by, String user, Role role) {
        sheets.load(sheetId, by);
        // Lock the sheet, so a role given at the same moment to the same name is not stored twice.
        sheets.lock(sheetId);
        SheetRole saved = roles.findBySheetIdAndUser(sheetId, user).orElse(null);
        if (saved == null) {
            saved = roles.saveAndFlush(new SheetRole(sheetId, user, role));
        } else {
            saved.setRole(role);
        }
        audit.change("ROLE_SET", sheetId, by, "user=" + user, "role=" + role.label());
        return saved;
    }
}
