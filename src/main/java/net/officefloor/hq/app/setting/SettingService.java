package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A sheet's settings: the role each user name has on it, and its clerk limit. */
@Service
public class SettingService {

    private final SheetService sheets;
    private final SheetRepository sheetRepository;
    private final SheetRoleRepository roles;
    private final Audit audit;

    public SettingService(SheetService sheets, SheetRepository sheetRepository, SheetRoleRepository roles,
            Audit audit) {
        this.sheets = sheets;
        this.sheetRepository = sheetRepository;
        this.roles = roles;
        this.audit = audit;
    }

    /** The roles assigned on the sheet, by user name. */
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserAsc(sheetId);
    }

    /** The user's role on the sheet: clerk unless assigned another. */
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** The sheet's clerk limit; null when there is none. */
    public BigDecimal clerkLimit(String sheetId) {
        return sheets.load(sheetId).getClerkLimit();
    }

    /** Assign a user name a role on the sheet. */
    @Transactional
    public SheetRole setRole(String sheetId, String by, String user, Role role) {
        sheets.lock(sheetId);
        SheetRole saved = roles.findBySheetIdAndUser(sheetId, user)
                .map(existing -> {
                    existing.setRole(role);
                    return existing;
                })
                .orElseGet(() -> new SheetRole(sheetId, user, role));
        saved = roles.saveAndFlush(saved);
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + by + " user=" + user + " role=" + role.label());
        return saved;
    }

    /** Change the sheet's clerk limit; null removes it. */
    @Transactional
    public Sheet setClerkLimit(String sheetId, String by, BigDecimal limit) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        BigDecimal scaled = limit != null ? limit.setScale(2, RoundingMode.UNNECESSARY) : null;
        sheet.setClerkLimit(scaled);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=clerk-limit value="
                + (scaled != null ? scaled.toPlainString() : ""));
        return saved;
    }
}
