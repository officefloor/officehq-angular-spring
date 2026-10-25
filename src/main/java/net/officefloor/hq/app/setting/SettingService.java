package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The sheet's settings (such as the clerk limit) and the roles it assigns user names. */
@Service
public class SettingService {

    /** The key of the clerk limit: the highest due a clerk may settle; no value means no limit. */
    public static final String CLERK_LIMIT = "clerk-limit";

    /** The key of the floor: the least a claim's live due can be; the sheet starts at {@link #DEFAULT_FLOOR}. */
    public static final String FLOOR = "floor";

    /** The floor of a sheet that has not set one. */
    public static final BigDecimal DEFAULT_FLOOR = new BigDecimal("1.00");

    /** The key of the terms: the days after a claim's date it is due by; the sheet starts at {@link #DEFAULT_TERMS}. */
    public static final String TERMS = "terms";

    /** The terms of a sheet that has not set them, in days. */
    public static final int DEFAULT_TERMS = 14;

    /** The key of the late percent: what an overdue claim gains of its figure for every full 30 days
     *  overdue; the sheet starts at {@link #DEFAULT_LATE_PCT}. */
    public static final String LATE_PCT = "late-pct";

    /** The late percent of a sheet that has not set one. */
    public static final BigDecimal DEFAULT_LATE_PCT = new BigDecimal("0.00");

    private final SheetSettingRepository settings;
    private final SheetRoleRepository roles;
    private final SheetService sheets;
    private final Rollup rollup;
    private final Audit audit;

    public SettingService(SheetSettingRepository settings, SheetRoleRepository roles, SheetService sheets,
            Rollup rollup, Audit audit) {
        this.settings = settings;
        this.roles = roles;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's floor with two decimals: the one it set, else {@link #DEFAULT_FLOOR}. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId) {
        return floorOf(settings, sheetId);
    }

    /** The sheet's floor as the store holds it, within the caller's transaction. */
    public static BigDecimal floorOf(SheetSettingRepository settings, String sheetId) {
        return settings.findBySheetIdAndKey(sheetId, FLOOR).map((s) -> new BigDecimal(s.getValue()))
                .orElse(DEFAULT_FLOOR);
    }

    /** Set the sheet's floor, kept to two decimals; the live dues it moves are audited after it. */
    @Transactional
    public BigDecimal setFloor(String sheetId, String user, BigDecimal floor) {
        sheets.load(sheetId);
        BigDecimal value = floor.setScale(2);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, FLOOR).orElse(null);
        try {
            if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, FLOOR, value.toPlainString()));
            } else {
                setting.setValue(value.toPlainString());
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + FLOOR
                + " value=" + value.toPlainString());
        rollup.recordChanges(user, before);
        return value;
    }

    /** The sheet's terms in days: the ones it set, else {@link #DEFAULT_TERMS}. */
    @Transactional(readOnly = true)
    public int terms(String sheetId) {
        return termsOf(settings, sheetId);
    }

    /** The sheet's terms as the store holds them, within the caller's transaction. */
    public static int termsOf(SheetSettingRepository settings, String sheetId) {
        return settings.findBySheetIdAndKey(sheetId, TERMS).map((s) -> Integer.parseInt(s.getValue()))
                .orElse(DEFAULT_TERMS);
    }

    /** Set the sheet's terms, in days. */
    @Transactional
    public int setTerms(String sheetId, String user, int days) {
        sheets.load(sheetId);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, TERMS).orElse(null);
        try {
            if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, TERMS, Integer.toString(days)));
            } else {
                setting.setValue(Integer.toString(days));
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + TERMS
                + " value=" + days);
        rollup.recordChanges(user, before);
        return days;
    }

    /** The sheet's late percent with two decimals: the one it set, else {@link #DEFAULT_LATE_PCT}. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String sheetId) {
        return latePctOf(settings, sheetId);
    }

    /** The sheet's late percent as the store holds it, within the caller's transaction. */
    public static BigDecimal latePctOf(SheetSettingRepository settings, String sheetId) {
        return settings.findBySheetIdAndKey(sheetId, LATE_PCT).map((s) -> new BigDecimal(s.getValue()))
                .orElse(DEFAULT_LATE_PCT);
    }

    /** Set the sheet's late percent, kept to two decimals; the late charges it moves are audited after it. */
    @Transactional
    public BigDecimal setLatePct(String sheetId, String user, BigDecimal pct) {
        sheets.load(sheetId);
        BigDecimal value = pct.setScale(2);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, LATE_PCT).orElse(null);
        try {
            if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, LATE_PCT, value.toPlainString()));
            } else {
                setting.setValue(value.toPlainString());
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + LATE_PCT
                + " value=" + value.toPlainString());
        rollup.recordChanges(user, before);
        return value;
    }

    /** The sheet's clerk limit with two decimals, or null when there is no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return settings.findBySheetIdAndKey(sheetId, CLERK_LIMIT).map((s) -> new BigDecimal(s.getValue()))
                .orElse(null);
    }

    /** Set the sheet's clerk limit, kept to two decimals; null clears it, leaving no limit. */
    @Transactional
    public BigDecimal setClerkLimit(String sheetId, String user, BigDecimal limit) {
        sheets.load(sheetId);
        BigDecimal value = limit == null ? null : limit.setScale(2);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, CLERK_LIMIT).orElse(null);
        try {
            if (value == null) {
                if (setting != null) {
                    settings.delete(setting);
                    settings.flush();
                }
            } else if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, CLERK_LIMIT, value.toPlainString()));
            } else {
                setting.setValue(value.toPlainString());
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + CLERK_LIMIT
                + " value=" + (value == null ? "" : value.toPlainString()));
        return value;
    }

    /** The roles the sheet has assigned, by user name; any other name is a clerk. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** The role the sheet gives a user name: a clerk unless it was assigned another. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUserName(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** Assign a user name a role on the sheet, replacing the one it had. */
    @Transactional
    public SheetRole setRole(String sheetId, String user, String userName, Role role) {
        sheets.load(sheetId);
        SheetRole assigned = roles.findBySheetIdAndUserName(sheetId, userName).orElse(null);
        if (assigned == null) {
            assigned = new SheetRole(sheetId, userName, role);
        } else {
            assigned.setRole(role);
        }
        SheetRole saved;
        try {
            saved = roles.saveAndFlush(assigned);
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The role was changed by someone else.");
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + userName
                + " role=" + role.label());
        return saved;
    }
}
