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
    private final Audit audit;

    public RoleService(RoleRepository roles, SheetRepository sheets, Audit audit) {
        this.roles = roles;
        this.sheets = sheets;
        this.audit = audit;
    }

    public List<SheetRole> list(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** The user's role on the sheet: clerk unless one was set. */
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUserName(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
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
