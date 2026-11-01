package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet's settings: the role each user name has (clerk unless given another) and the clerk
 * limit, the highest due a clerk can settle (none: no limit); and the floor, the least a claim's
 * live due can be; and the terms, the days after its date by which a claim is due; and the late
 * percent, which an overdue claim's due gains of its figure for every full 30 days overdue; and the
 * policy date (none: the sheet's today), before which a claim's live figures round half up, else half to even.
 */
@Service
public class SettingService {

    private final SheetRepository sheets;
    private final SheetRoleRepository roles;
    private final RollupService rollup;
    private final Audit audit;

    public SettingService(SheetRepository sheets, SheetRoleRepository roles, RollupService rollup, Audit audit) {
        this.sheets = sheets;
        this.roles = roles;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserName(sheetId);
    }

    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        // not mapped through the Optional: a null limit (none) would read as no sheet
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getClerkLimit();
    }

    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getFloor).orElseThrow(SheetNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public int terms(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getTerms).orElseThrow(SheetNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public BigDecimal latePct(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getLatePct).orElseThrow(SheetNotFoundException::new);
    }

    /** The policy date as set; null when blank, which means the sheet's today. */
    @Transactional(readOnly = true)
    public LocalDate policyDate(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getPolicyDate();
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

    /** Change the terms; no figure moves with them. */
    @Transactional
    public int setTerms(String sheetId, String user, int terms) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setTerms(terms);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=terms value=" + terms);
        return terms;
    }

    /** Change the floor; the live dues it moves are audited after its own line. */
    @Transactional
    public BigDecimal setFloor(String sheetId, String user, BigDecimal floor) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setFloor(floor);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=floor value="
                + floor.toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return floor;
    }

    /** Change the late percent; the dues it moves are audited after its own line. */
    @Transactional
    public BigDecimal setLatePct(String sheetId, String user, BigDecimal latePct) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setLatePct(latePct);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=late-pct value="
                + latePct.toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return latePct;
    }

    /** Change the policy date (null: the sheet's today); the live dues it moves are audited after its own line. */
    @Transactional
    public LocalDate setPolicyDate(String sheetId, String user, LocalDate policyDate) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setPolicyDate(policyDate);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=policy-date value="
                + (policyDate == null ? "" : policyDate.toString()));
        rollup.recordChanges(sheetId, user, before);
        return policyDate;
    }
}
