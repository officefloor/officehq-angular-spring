package net.officefloor.hq.app.access;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may do what on a sheet: the role each user name has (clerk unless set to steward) and the
 * clerk limit, the most due a clerk may settle on a claim (none: no limit); and the sheet's floor, the
 * least live due a claim has.
 */
@Service
public class AccessService {

    public static final String CLERK_LIMIT = "clerk-limit";

    public static final String FLOOR = "floor";

    private final SheetRoleRepository roles;
    private final SheetRepository sheetRows;
    private final SheetService sheets;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;

    public AccessService(SheetRoleRepository roles, SheetRepository sheetRows, SheetService sheets,
            Rollup rollup, ApplicationEventPublisher events) {
        this.rollup = rollup;
        this.roles = roles;
        this.sheetRows = sheetRows;
        this.sheets = sheets;
        this.events = events;
    }

    /** The roles set on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserName(sheetId);
    }

    /** The user's role on the sheet: clerk unless one was set. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUserName(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return sheetRows.findById(sheetId).map(Sheet::getClerkLimit).orElse(null);
    }

    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId) {
        return sheets.floor(sheetId);
    }

    /** Give a user name a role on the sheet, replacing the one it had. */
    @Transactional
    public SheetRole setRole(String sheetId, String user, String name, Role role) {
        sheets.load(sheetId, user);
        SheetRole row = roles.findBySheetIdAndUserName(sheetId, name).orElse(null);
        if (row == null) {
            row = roles.saveAndFlush(new SheetRole(sheetId, name, role));
        } else {
            row.setRole(role);
            roles.flush();
        }
        events.publishEvent(new RoleSet(sheetId, user, name, role.key()));
        return row;
    }

    /**
     * Save the settings given, as written (blank: none, or the default), in one change; a setting left
     * out (null) is kept as it is.
     */
    @Transactional
    public void setSettings(String sheetId, String user, String clerkLimit, String floor) {
        if (clerkLimit != null) {
            setClerkLimit(sheetId, user, clerkLimit.isEmpty() ? null : new BigDecimal(clerkLimit));
        }
        if (floor != null) {
            setFloor(sheetId, user, floor.isEmpty() ? null : new BigDecimal(floor));
        }
    }

    /** Save the sheet's clerk limit; null removes it. */
    @Transactional
    public BigDecimal setClerkLimit(String sheetId, String user, BigDecimal limit) {
        Sheet sheet = sheets.load(sheetId, user);
        BigDecimal scaled = limit == null ? null : limit.setScale(2, RoundingMode.HALF_UP);
        sheet.setClerkLimit(scaled);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SettingChanged(sheetId, user, CLERK_LIMIT, scaled == null ? "" : scaled.toPlainString()));
        return scaled;
    }

    /**
     * Save the sheet's floor; null puts back the one a sheet starts with. Every live due moves with it;
     * a settled, stated or closed figure keeps the one it had.
     */
    @Transactional
    public BigDecimal setFloor(String sheetId, String user, BigDecimal floor) {
        Sheet sheet = sheets.load(sheetId, user);
        BigDecimal scaled = floor == null ? Sheet.DEFAULT_FLOOR : floor.setScale(2, RoundingMode.HALF_UP);
        Rollup.Figures before = rollup.figures(sheetId);
        sheet.setFloor(scaled);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SettingChanged(sheetId, user, FLOOR, scaled.toPlainString()));
        rollup.changed(sheetId, user, before);
        return scaled;
    }

    /**
     * Refuse a settle by a clerk of a claim whose due is above the clerk limit. A due of n/a (null)
     * is within any limit; a steward settles anything.
     */
    @Transactional(readOnly = true)
    public void requireCanSettle(String sheetId, String user, BigDecimal due) {
        if (due == null) {
            return;
        }
        BigDecimal limit = clerkLimit(sheetId);
        if (limit != null && due.compareTo(limit) > 0 && roleOf(sheetId, user) == Role.CLERK) {
            throw new OverClerkLimit(due, limit);
        }
    }
}
