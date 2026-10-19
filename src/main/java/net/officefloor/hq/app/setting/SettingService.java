package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A sheet's settings: the role each user name has on it, and its clerk limit. */
@Service
public class SettingService {

    private final SheetRoleRepository roles;
    private final SheetRepository sheetRepository;
    private final SheetService sheets;
    private final Audit audit;

    public SettingService(SheetRoleRepository roles, SheetRepository sheetRepository, SheetService sheets,
            Audit audit) {
        this.roles = roles;
        this.sheetRepository = sheetRepository;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The roles given on the sheet. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        sheets.today(sheetId); // the sheet must exist
        return roles.findBySheetIdOrderById(sheetId);
    }

    /** The role a user name has on the sheet: a clerk unless given another. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** Give a user name a role on the sheet, replacing any it had. */
    @Transactional
    public SheetRole setRole(String user, String sheetId, String name, Role role) {
        sheets.lock(sheetId);
        SheetRole given = roles.findBySheetIdAndUser(sheetId, name).orElse(null);
        if (given == null) {
            given = roles.saveAndFlush(new SheetRole(sheetId, name, role));
        } else {
            given.setRole(role);
            roles.flush();
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + name + " role=" + role.code());
        return given;
    }

    /** The sheet's clerk limit; none means no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return find(sheetId).getClerkLimit();
    }

    /** Set the sheet's clerk limit; none removes it. */
    @Transactional
    public BigDecimal setClerkLimit(String user, String sheetId, BigDecimal limit) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        BigDecimal value = limit != null ? limit.setScale(2) : null;
        sheet.setClerkLimit(value);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=clerk-limit value="
                + (value != null ? value.toPlainString() : ""));
        return value;
    }

    /** Refuse a settle by a clerk of a claim whose due is above the clerk limit; a due of n/a is within any
     * limit, and a steward settles anything. */
    @Transactional(readOnly = true)
    public void requireMaySettle(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = clerkLimit(sheetId);
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return;
        }
        if (roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A clerk cannot settle a claim whose due is above the clerk limit of " + limit.toPlainString()
                            + ".");
        }
    }

    private Sheet find(String sheetId) {
        return sheetRepository.findById(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
    }
}
