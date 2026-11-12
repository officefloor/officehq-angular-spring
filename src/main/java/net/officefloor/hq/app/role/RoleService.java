package net.officefloor.hq.app.role;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the role each sheet gives a user name: clerk (any name with none), steward or viewer. */
@Service
public class RoleService {

    private final SheetRoleRepository roles;
    private final SheetRepository sheets;
    private final Audit audit;

    public RoleService(SheetRoleRepository roles, SheetRepository sheets, Audit audit) {
        this.roles = roles;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SheetRole> list(String sheetId) {
        requireSheet(sheetId);
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** Give a user name a role on the sheet, replacing the one it had. */
    @Transactional
    public SheetRole set(String sheetId, String user, String name, String role) {
        requireSheet(sheetId);
        SheetRole row = roles.findBySheetIdAndUserName(sheetId, name).orElse(null);
        if (row == null) {
            try {
                row = roles.saveAndFlush(new SheetRole(sheetId, name, role));
            } catch (DataIntegrityViolationException e) {
                throw new RoleConflictException();
            }
        } else {
            row.setRole(role);
            row = roles.saveAndFlush(row);
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + name + " role=" + role);
        return row;
    }

    /** Whether the sheet makes the user name a steward. */
    @Transactional(readOnly = true)
    public boolean isSteward(String sheetId, String name) {
        return roles.findBySheetIdAndUserName(sheetId, name)
                .map(r -> SheetRole.STEWARD.equals(r.getRole()))
                .orElse(false);
    }

    /** Whether the sheet makes the user name a viewer, who sees the sheet and changes nothing. */
    @Transactional(readOnly = true)
    public boolean isViewer(String sheetId, String name) {
        return roles.findBySheetIdAndUserName(sheetId, name)
                .map(r -> SheetRole.VIEWER.equals(r.getRole()))
                .orElse(false);
    }

    /** Refuse any change by a viewer. */
    public void requireNotViewer(String sheetId, String name) {
        if (isViewer(sheetId, name)) {
            throw new ActionNotAllowedException("change anything");
        }
    }

    /** Whether the user's role on the sheet may take an action, by the sheet's permission matrix (an action
     *  with no roles set keeps the standing rule). */
    @Transactional(readOnly = true)
    public boolean allows(String sheetId, String name, String action) {
        if (isViewer(sheetId, name)) {
            return false;
        }
        String role = isSteward(sheetId, name) ? SheetRole.STEWARD : SheetRole.CLERK;
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).allows(action, role);
    }

    /** Refuse an action the user's role on the sheet may not take. */
    public void require(String sheetId, String name, String action) {
        if (!allows(sheetId, name, action)) {
            throw new ActionNotAllowedException(action);
        }
    }

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
    }
}
