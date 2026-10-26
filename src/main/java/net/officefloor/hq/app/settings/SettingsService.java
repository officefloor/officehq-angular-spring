package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A sheet's settings: the role each user name has, the clerk limit, the floor, the terms, the late percent and the policy date. */
@Service
public class SettingsService {

    private final SheetRoleRepository roles;
    private final SheetRepository sheetRepository;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public SettingsService(SheetRoleRepository roles, SheetRepository sheetRepository, SheetService sheets,
            RollupService rollup, Audit audit) {
        this.roles = roles;
        this.sheetRepository = sheetRepository;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The roles set on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserAsc(sheetId);
    }

    /** The user's role on the sheet: clerk unless set otherwise. */
    @Transactional(readOnly = true)
    public String roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(SheetRole.CLERK);
    }

    /** Sets the user's role on the sheet; refused unless it is clerk or steward. */
    @Transactional
    public SheetRole setRole(String by, String sheetId, String user, String role) {
        if (!SheetRole.CLERK.equals(role) && !SheetRole.STEWARD.equals(role)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A role is clerk or steward.");
        }
        sheets.lock(sheetId);
        SheetRole r = roles.findBySheetIdAndUser(sheetId, user).orElseGet(() -> new SheetRole(sheetId, user, role));
        r.setRole(role);
        SheetRole saved;
        try {
            saved = roles.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That role was just set by someone else.");
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + by + " user=" + user + " role=" + role);
        return saved;
    }

    /** Sets the sheet's clerk limit; null means no limit. */
    @Transactional
    public Sheet setClerkLimit(String by, String sheetId, BigDecimal limit) {
        Sheet sheet = sheets.lock(sheetId);
        BigDecimal scaled = limit == null ? null : limit.setScale(2, RoundingMode.UNNECESSARY);
        sheet.setClerkLimit(scaled);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=clerk-limit value="
                + (scaled == null ? "" : scaled.toPlainString()));
        return saved;
    }

    /** Sets the sheet's terms: the days after a claim's date it is due by. */
    @Transactional
    public Sheet setTerms(String by, String sheetId, int terms) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setTerms(terms);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=terms value=" + terms);
        return saved;
    }

    /**
     * Sets the sheet's late percent: the percent of an overdue claim's figure added for every full 30 days it is
     * overdue. Settled figures keep what they were; the live figures it moves are rolled up.
     */
    @Transactional
    public Sheet setLatePct(String by, String sheetId, BigDecimal latePct) {
        Sheet sheet = sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal scaled = latePct.setScale(2, RoundingMode.UNNECESSARY);
        sheet.setLatePct(scaled);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=late-pct value="
                + scaled.toPlainString());
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Sets the sheet's policy date (null for blank: the sheet's today): a claim rounded by a date before it rounds
     * half up, one on or after it half to even. Settled and stated figures keep what they were; the live figures it
     * moves are rolled up.
     */
    @Transactional
    public Sheet setPolicyDate(String by, String sheetId, LocalDate policyDate) {
        Sheet sheet = sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setPolicyDate(policyDate);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=policy-date value="
                + (policyDate == null ? "" : policyDate.toString()));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Sets the sheet's period numbering from (null for blank: off): while it is set and the sheet's today is on or
     * after it, a claim opened is numbered within its period. Claims already numbered keep their numbers.
     */
    @Transactional
    public Sheet setPeriodNumberingFrom(String by, String sheetId, LocalDate from) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setPeriodNumberingFrom(from);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=period-numbering-from value="
                + (from == null ? "" : from.toString()));
        return saved;
    }

    /**
     * Sets the sheet's floor: the least a claim's live due can be. Settled, stated and closed-period figures keep
     * what they were; the live figures it moves are rolled up.
     */
    @Transactional
    public Sheet setFloor(String by, String sheetId, BigDecimal floor) {
        Sheet sheet = sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal scaled = floor.setScale(2, RoundingMode.UNNECESSARY);
        sheet.setFloor(scaled);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=floor value="
                + scaled.toPlainString());
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Whether the user settling a claim with the due (null for n/a) needs a steward's approval: they are a clerk and
     * the due is above the sheet's clerk limit. A steward settles anything, and an n/a due is within any limit.
     */
    @Transactional(readOnly = true)
    public boolean aboveClerkLimit(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = sheets.load(sheetId).getClerkLimit();
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return false;
        }
        return !isSteward(sheetId, user);
    }

    /** Whether the user is a steward of the sheet. */
    @Transactional(readOnly = true)
    public boolean isSteward(String sheetId, String user) {
        return SheetRole.STEWARD.equals(roleOf(sheetId, user));
    }
}
