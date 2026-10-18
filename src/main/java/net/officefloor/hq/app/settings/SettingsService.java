package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The sheet's settings (its clerk limit) and the roles it gives user names. */
@Service
public class SettingsService {

    private final SheetRoleRepository roles;
    private final SheetService sheets;
    private final Audit audit;

    public SettingsService(SheetRoleRepository roles, SheetService sheets, Audit audit) {
        this.roles = roles;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's clerk limit; null when there is none. */
    @Transactional
    public BigDecimal clerkLimit(String sheetId, String user) {
        return sheets.load(sheetId, user).getClerkLimit();
    }

    /** Set the sheet's clerk limit (null: no limit). */
    @Transactional
    public BigDecimal setClerkLimit(String sheetId, String user, BigDecimal limit) {
        Sheet sheet = sheets.lock(sheetId, user);
        sheet.setClerkLimit(limit);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=clerk-limit value=" + (limit == null ? "" : limit.toPlainString()));
        return limit;
    }

    /** The roles the sheet gives user names, by name; a name not among them is a clerk. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** The role the sheet gives a user name; clerk when it gives none. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String userName) {
        return roles.findBySheetIdAndUserName(sheetId, userName).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** Give a user name a role, replacing the one it had. */
    @Transactional
    public SheetRole setRole(String sheetId, String user, String userName, Role role) {
        sheets.lock(sheetId, user);
        SheetRole entry = roles.findBySheetIdAndUserName(sheetId, userName)
                .orElseGet(() -> new SheetRole(sheetId, userName, role));
        entry.setRole(role);
        SheetRole saved = roles.saveAndFlush(entry);
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user
                + " user=" + userName + " role=" + role.name().toLowerCase());
        return saved;
    }
}
