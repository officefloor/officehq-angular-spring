package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The sheet's settings (its clerk limit, floor, terms, late percent, policy date and period numbering from-date) and the role each user name holds on it. */
@Service
public class SettingsService {

    /** The key the clerk limit is audited under. */
    public static final String CLERK_LIMIT = "clerk-limit";
    /** The key the floor is audited under. */
    public static final String FLOOR = "floor";
    /** The key the terms are audited under. */
    public static final String TERMS = "terms";
    /** The key the late percent is audited under. */
    public static final String LATE_PCT = "late-pct";
    /** The key the policy date is audited under. */
    public static final String POLICY_DATE = "policy-date";

    /** The key the period numbering from-date is audited under. */
    public static final String PERIOD_NUMBERING_FROM = "period-numbering-from";

    private final SheetRoleRepository roles;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public SettingsService(SheetRoleRepository roles, SheetService sheets, RollupService rollup, Audit audit) {
        this.roles = roles;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's clerk limit; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getClerkLimit).orElse(null);
    }

    /** Set the sheet's clerk limit (null: no limit), the sheet created first if it was never loaded. */
    @Transactional
    public BigDecimal setClerkLimit(String sheetId, String user, BigDecimal limit) {
        Sheet sheet = sheets.load(sheetId, user);
        BigDecimal scaled = limit == null ? null : limit.setScale(2);
        sheet.setClerkLimit(scaled);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + CLERK_LIMIT + " value=" + (scaled == null ? "" : scaled.toPlainString()));
        return scaled;
    }

    /** The sheet's floor: a claim's live due is at least it. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId) {
        return sheets.floor(sheetId);
    }

    /** Set the sheet's floor, the sheet created first if it was never loaded; every live due (and
     *  so every total) moves with it, while settled, bound and closed figures stay as they are. */
    @Transactional
    public BigDecimal setFloor(String sheetId, String user, BigDecimal floor) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal scaled = floor.setScale(2);
        sheet.setFloor(scaled);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + FLOOR + " value=" + scaled.toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return scaled;
    }

    /** The sheet's terms: a claim is due by its date plus this many days. */
    @Transactional(readOnly = true)
    public int terms(String sheetId) {
        return sheets.terms(sheetId);
    }

    /** Set the sheet's terms in days, the sheet created first if it was never loaded; every claim's
     *  due-by (and so whether it is overdue, and its late charge) moves with them. */
    @Transactional
    public int setTerms(String sheetId, String user, int terms) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setTerms(terms);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + TERMS + " value=" + terms);
        rollup.recordChanges(sheetId, user, before);
        return terms;
    }

    /** The sheet's late percent: an overdue claim's due gains it for every full 30 days overdue. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String sheetId) {
        return sheets.latePct(sheetId);
    }

    /** Set the sheet's late percent, the sheet created first if it was never loaded; every overdue
     *  OPEN claim's late charge (and so every total) moves with it, while settled figures stay. */
    @Transactional
    public BigDecimal setLatePct(String sheetId, String user, BigDecimal latePct) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal scaled = latePct.setScale(2);
        sheet.setLatePct(scaled);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + LATE_PCT + " value=" + scaled.toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return scaled;
    }

    /** The sheet's policy date as set; null when blank (the sheet's today is the policy date). */
    @Transactional(readOnly = true)
    public LocalDate policyDate(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getPolicyDate).orElse(null);
    }

    /** Set the sheet's policy date (null: blank, following the sheet's today), the sheet created
     *  first if it was never loaded; every live due's rounding (and so every total) moves with it,
     *  while settled and bound figures stay as they are. */
    @Transactional
    public LocalDate setPolicyDate(String sheetId, String user, LocalDate policyDate) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setPolicyDate(policyDate);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + POLICY_DATE + " value=" + (policyDate == null ? "" : policyDate));
        rollup.recordChanges(sheetId, user, before);
        return policyDate;
    }

    /** The sheet's period numbering from-date; null when blank (off). */
    @Transactional(readOnly = true)
    public LocalDate periodNumberingFrom(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getPeriodNumberingFrom).orElse(null);
    }

    /** Set the sheet's period numbering from-date (null: off), the sheet created first if it was
     *  never loaded; numbers already given stay as they are. */
    @Transactional
    public LocalDate setPeriodNumberingFrom(String sheetId, String user, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setPeriodNumberingFrom(from);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + PERIOD_NUMBERING_FROM + " value=" + (from == null ? "" : from));
        return from;
    }

    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserAsc(sheetId);
    }

    /** The role a user name holds on the sheet: a clerk unless set otherwise. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** Give a user name a role on the sheet, the sheet created first if it was never loaded. */
    @Transactional
    public SheetRole setRole(String sheetId, String user, String name, Role role) {
        sheets.load(sheetId, user);
        SheetRole held = roles.findBySheetIdAndUser(sheetId, name).orElse(null);
        try {
            if (held == null) {
                held = roles.saveAndFlush(new SheetRole(sheetId, name, role));
            } else {
                held.setRole(role);
                roles.flush();
            }
        } catch (DataIntegrityViolationException e) {
            // set by another user at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Role changed at the same moment");
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + name + " role=" + role.key());
        return held;
    }

    /** Whether a user may settle a claim showing a due (null: n/a, within any limit): a steward
     *  settles anything, a clerk only a due at or below the sheet's clerk limit, if it has one. */
    @Transactional(readOnly = true)
    public boolean maySettle(String sheetId, String user, BigDecimal due) {
        if (due == null || roleOf(sheetId, user) == Role.STEWARD) {
            return true;
        }
        BigDecimal limit = clerkLimit(sheetId);
        return limit == null || due.compareTo(limit) <= 0;
    }
}
