package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A sheet's settings: the role each user name has on it, and its clerk limit, its floor, its terms and
 *  its late percent, its policy date and its period numbering from. */
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

    /** The sheet's floor: the least a claim's live due can be. */
    public BigDecimal floor(String sheetId) {
        return sheets.load(sheetId).getFloor();
    }

    /** The sheet's terms in days: a claim is due by its date plus them. */
    public int terms(String sheetId) {
        return sheets.load(sheetId).getTerms();
    }

    /** The sheet's late percent: an overdue claim's due gains it of its figure for every full 30 days
     *  it is overdue. */
    public BigDecimal latePct(String sheetId) {
        return sheets.load(sheetId).getLatePct();
    }

    /** The sheet's policy date as set; null when it is blank (the sheet's today applies). */
    public LocalDate policyDateSet(String sheetId) {
        return sheets.load(sheetId).getPolicyDate();
    }

    /** The sheet's policy date in force: as set, else the sheet's today. */
    public LocalDate policyDate(String sheetId) {
        LocalDate set = policyDateSet(sheetId);
        return set != null ? set : sheets.today(sheetId);
    }

    /** The sheet's period numbering from; null when it is blank (off). */
    public LocalDate periodNumberingFrom(String sheetId) {
        return sheets.load(sheetId).getPeriodNumberingFrom();
    }

    /** How a claim dated on the given date rounds: half up before the policy date, half to even on or
     *  after it. */
    public RoundingMode roundingOn(String sheetId, LocalDate date) {
        return date.isBefore(policyDate(sheetId)) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
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

    /** Change the sheet's floor. */
    @Transactional
    public Sheet setFloor(String sheetId, String by, BigDecimal floor) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        BigDecimal scaled = floor.setScale(2, RoundingMode.UNNECESSARY);
        sheet.setFloor(scaled);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=floor value="
                + scaled.toPlainString());
        return saved;
    }

    /** Change the sheet's terms. */
    @Transactional
    public Sheet setTerms(String sheetId, String by, int terms) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setTerms(terms);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=terms value=" + terms);
        return saved;
    }

    /** Change the sheet's late percent. */
    @Transactional
    public Sheet setLatePct(String sheetId, String by, BigDecimal latePct) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        BigDecimal scaled = latePct.setScale(2, RoundingMode.UNNECESSARY);
        sheet.setLatePct(scaled);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=late-pct value="
                + scaled.toPlainString());
        return saved;
    }

    /** Change the sheet's policy date; null leaves it blank (the sheet's today). */
    @Transactional
    public Sheet setPolicyDate(String sheetId, String by, LocalDate policyDate) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setPolicyDate(policyDate);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=policy-date value="
                + (policyDate != null ? policyDate.toString() : ""));
        return saved;
    }

    /** Change the sheet's period numbering from; null leaves it blank (off). */
    @Transactional
    public Sheet setPeriodNumberingFrom(String sheetId, String by, LocalDate from) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setPeriodNumberingFrom(from);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=period-numbering-from value="
                + (from != null ? from.toString() : ""));
        return saved;
    }
}
