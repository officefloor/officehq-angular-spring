package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet's settings: the role each user name has (clerk unless given another) and the clerk
 * limit, the highest due a clerk can settle (none: no limit).
 */
@Service
public class SettingService {

    private final SheetRepository sheets;
    private final SheetRoleRepository roles;
    private final Audit audit;

    public SettingService(SheetRepository sheets, SheetRoleRepository roles, Audit audit) {
        this.sheets = sheets;
        this.roles = roles;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserName(sheetId);
    }

    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getClerkLimit).orElseThrow(SheetNotFoundException::new);
    }

    /** The user's role on the sheet: clerk unless given another. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String userName) {
        return roles.findBySheetIdAndUserName(sheetId, userName.strip()).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    @Transactional
    public SheetRole setRole(String sheetId, String user, String userName, Role role) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        String name = userName.strip();
        SheetRole saved = roles.findBySheetIdAndUserName(sheetId, name)
                .map(r -> { r.setRole(role); return r; })
                .orElseGet(() -> new SheetRole(sheetId, name, role));
        saved = roles.saveAndFlush(saved);
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user.strip() + " user=" + name + " role=" + role.label());
        return saved;
    }

    @Transactional
    public BigDecimal setClerkLimit(String sheetId, String user, BigDecimal limit) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setClerkLimit(limit);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=clerk-limit value="
                + (limit == null ? "" : limit.toPlainString()));
        return limit;
    }
}
