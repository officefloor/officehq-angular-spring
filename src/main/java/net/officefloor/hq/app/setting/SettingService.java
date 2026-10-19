package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.rollup.RollupService.Snapshot;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A sheet's settings: the role each user name has on it, its clerk limit, its floor, its terms and its late percent. */
@Service
public class SettingService {

    private final SheetRoleRepository roles;
    private final SheetRepository sheetRepository;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public SettingService(SheetRoleRepository roles, SheetRepository sheetRepository, SheetService sheets,
            RollupService rollup, Audit audit) {
        this.roles = roles;
        this.sheetRepository = sheetRepository;
        this.sheets = sheets;
        this.rollup = rollup;
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

    /** Save the settings given, the clerk limit, the floor, the terms then the late percent, together; a setting
     * not given (null) is left as it is, and an empty clerk limit removes it. */
    @Transactional
    public void save(String user, String sheetId, Optional<BigDecimal> clerkLimit, BigDecimal floor,
            Integer terms, BigDecimal latePct) {
        if (clerkLimit != null) {
            setClerkLimit(user, sheetId, clerkLimit.orElse(null));
        }
        if (floor != null) {
            setFloor(user, sheetId, floor);
        }
        if (terms != null) {
            setTerms(user, sheetId, terms);
        }
        if (latePct != null) {
            setLatePct(user, sheetId, latePct);
        }
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

    /** The sheet's floor: the least a claim's live due comes to. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId) {
        return find(sheetId).getFloor();
    }

    /** Set the sheet's floor; it moves every live due below it, and the figures it moves are written after. */
    @Transactional
    public BigDecimal setFloor(String user, String sheetId, BigDecimal floor) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        Snapshot before = rollup.snapshot(sheetId);
        BigDecimal value = floor.setScale(2);
        sheet.setFloor(value);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=floor value="
                + value.toPlainString());
        rollup.recordChanges(user, sheetId, before);
        return value;
    }

    /** The sheet's terms: the days after its date a claim is due by. */
    @Transactional(readOnly = true)
    public int terms(String sheetId) {
        return find(sheetId).getTerms();
    }

    /** Set the sheet's terms; they move which claims are overdue, so the late charges, and the figures they move
     * are written after. */
    @Transactional
    public int setTerms(String user, String sheetId, int terms) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        Snapshot before = rollup.snapshot(sheetId);
        sheet.setTerms(terms);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=terms value=" + terms);
        rollup.recordChanges(user, sheetId, before);
        return terms;
    }

    /** The sheet's late percent: what an overdue claim gains of its figure for every full 30 days overdue. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String sheetId) {
        return find(sheetId).getLatePct();
    }

    /** Set the sheet's late percent; it moves every late charge, and the figures it moves are written after. */
    @Transactional
    public BigDecimal setLatePct(String user, String sheetId, BigDecimal latePct) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        Snapshot before = rollup.snapshot(sheetId);
        BigDecimal value = latePct.setScale(2);
        sheet.setLatePct(value);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=late-pct value="
                + value.toPlainString());
        rollup.recordChanges(user, sheetId, before);
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
