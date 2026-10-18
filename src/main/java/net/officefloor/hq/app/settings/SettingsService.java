package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The sheet's settings (its clerk limit and floor) and the roles it gives user names. */
@Service
public class SettingsService {

    private final SheetRoleRepository roles;
    private final SheetService sheets;
    private final RollupService rollups;
    private final Audit audit;

    public SettingsService(SheetRoleRepository roles, SheetService sheets, RollupService rollups, Audit audit) {
        this.roles = roles;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** The sheet's settings now. */
    @Transactional
    public Sheet settings(String sheetId, String user) {
        return sheets.load(sheetId, user);
    }

    /**
     * Save the settings given: {@code setClerkLimit} the clerk limit (null: no limit), a non-null
     * {@code floor} the floor; each one saved is audited, even when its value is the same. A floor
     * that moves live dues records the rollups that moved.
     */
    @Transactional
    public Sheet save(String sheetId, String user, boolean setClerkLimit, BigDecimal clerkLimit, BigDecimal floor) {
        Sheet sheet = sheets.lock(sheetId, user);
        if (setClerkLimit) {
            sheet.setClerkLimit(clerkLimit);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                    + " key=clerk-limit value=" + (clerkLimit == null ? "" : clerkLimit.toPlainString()));
        }
        if (floor != null) {
            RollupService.Rollup before = rollups.snapshot(sheetId);
            sheet.setFloor(floor);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                    + " key=floor value=" + floor.toPlainString());
            rollups.recordChanges(sheetId, user, before);
        }
        return sheet;
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
