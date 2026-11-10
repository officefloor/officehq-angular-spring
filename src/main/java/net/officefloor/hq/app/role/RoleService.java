package net.officefloor.hq.app.role;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The roles a sheet assigns to user names: clerk (the default for any name), steward or viewer. */
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
    public List<Role> list(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** The user's role on the sheet: clerk unless assigned otherwise. */
    @Transactional(readOnly = true)
    public String roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUserName(sheetId, user).map(Role::getRole).orElse(Role.CLERK);
    }

    /**
     * Refuse (403) an action the user's role may not take by the sheet's permission matrix (see
     * {@link net.officefloor.hq.app.sheet.Sheet#permits}).
     */
    @Transactional(readOnly = true)
    public void refuseUnlessPermitted(String sheetId, String user, String action) {
        if (!sheets.sheet(sheetId).permits(action, roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not " + action + " here.");
        }
    }

    /** Refuse (403) any change by a viewer: a viewer sees the sheet and may change nothing. */
    @Transactional(readOnly = true)
    public void refuseViewer(String sheetId, String user) {
        if (Role.VIEWER.equals(roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A viewer may not change anything here.");
        }
    }

    @Transactional
    public Role set(String sheetId, String user, String name, String role) {
        refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        Role assigned = roles.findBySheetIdAndUserName(sheetId, name).orElseGet(() -> new Role(sheetId, name, role));
        assigned.setRole(role);
        Role saved;
        try {
            saved = roles.saveAndFlush(assigned);
        } catch (DataIntegrityViolationException e) {
            // another user assigned this name a role at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This role was just set by someone else.");
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + name + " role=" + role);
        return saved;
    }
}
