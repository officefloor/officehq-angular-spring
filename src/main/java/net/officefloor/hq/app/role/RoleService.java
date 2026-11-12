package net.officefloor.hq.app.role;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the role each user name has on a sheet; a name with none is a clerk. */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final SheetRepository sheets;
    private final DelegationService delegations;
    private final Audit audit;

    public RoleService(RoleRepository roles, SheetRepository sheets, DelegationService delegations, Audit audit) {
        this.roles = roles;
        this.sheets = sheets;
        this.delegations = delegations;
        this.audit = audit;
    }

    public List<SheetRole> list(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** The user's role on the sheet: steward while a delegation covers the sheet's today, else clerk unless one was set. */
    public Role roleOf(String sheetId, String user) {
        if (delegations.delegatorOf(sheetId, user).isPresent()) {
            return Role.STEWARD;
        }
        return roles.findBySheetIdAndUserName(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** Whether the user is a viewer on the sheet, who may change nothing. */
    public boolean isViewer(String sheetId, String user) {
        return roleOf(sheetId, user) == Role.VIEWER;
    }

    /** Whether the user is a steward on the sheet, in their own right or by a delegation covering its today. */
    public boolean isSteward(String sheetId, String user) {
        return roleOf(sheetId, user) == Role.STEWARD;
    }

    /** Whether the user's role may take the action (open, settle, void, reassign, close, reopen) on the sheet. */
    public boolean may(String sheetId, String user, String action) {
        return sheets.findById(sheetId).orElseThrow(() -> new SheetNotFoundException(sheetId))
                .allows(action, roleOf(sheetId, user).value());
    }

    @Transactional
    public SheetRole set(String sheetId, String by, String user, Role role) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        SheetRole row = roles.findBySheetIdAndUserName(sheetId, user).orElse(null);
        if (row == null) {
            row = new SheetRole(sheetId, user, role);
        } else {
            row.setRole(role);
        }
        SheetRole saved;
        try {
            saved = roles.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            throw new RoleTakenException(sheetId, user);
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + by + " user=" + saved.getUserName()
                + " role=" + saved.getRole().value());
        return saved;
    }
}
