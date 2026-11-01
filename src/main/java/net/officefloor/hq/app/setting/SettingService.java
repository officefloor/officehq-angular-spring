package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.DatedSetting;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet's settings: the role each user name has (clerk unless given another) and the clerk
 * limit, the highest due a clerk can settle (none: no limit); and the floor, the least a claim's
 * live due can be; and the terms, the days after its date by which a claim is due (fixed when it is
 * added), set for the sheet and, overriding it, for any grade; and the late
 * percent, which an overdue claim's due gains of its figure for every full 30 days overdue. Those are
 * dated: each save records its value from a from-date (the sheet's today unless given), and a claim
 * takes the floor, late percent and terms in force on its date, the clerk limit and cap those in force
 * on the sheet's today (see {@link Sheet}). And the
 * policy date (none: the sheet's today), before which a claim's live figures round half up, else half to even;
 * and the period numbering from (none: off), from which a claim opened is numbered within its period;
 * and the cap (none: no cap), the most a person's due-total can be; and the proof threshold (none: none,
 * dated, taken on a claim's date), above which a claim's due needs a proof to be settled or approved;
 * and the grace days (none: off, not dated), past which an open period closes when the sheet's today changes;
 * and the permission matrix (not dated): for each action, the roles that may take it, blank keeping
 * its standing rule.
 */
@Service
public class SettingService {

    /** The grades a person can have, each of which can have its own terms. */
    public static final List<String> GRADES = List.of("A", "B", "C");

    /** The actions of the permission matrix, each with its standing rule: the roles that may take it while none are set. */
    public static final Map<String, Set<Role>> STANDING_PERMISSIONS;
    static {
        Map<String, Set<Role>> standing = new LinkedHashMap<>();
        for (String action : List.of("open", "settle", "void", "close")) standing.put(action, EnumSet.of(Role.CLERK, Role.STEWARD));
        for (String action : List.of("reassign", "reopen")) standing.put(action, EnumSet.of(Role.STEWARD));
        STANDING_PERMISSIONS = Map.copyOf(standing);
    }

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

    /** The clerk limit in force on the sheet's today; null means no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        // not mapped through the Optional: a null limit (none) would read as no sheet
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getClerkLimit();
    }

    /** The floor in force on the sheet's today. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId) {
        return sheets.findById(sheetId).map(s -> s.floorOn(s.getToday())).orElseThrow(SheetNotFoundException::new);
    }

    /** The floor in force on the date. */
    @Transactional(readOnly = true)
    public BigDecimal floorOn(String sheetId, LocalDate date) {
        return sheets.findById(sheetId).map(s -> s.floorOn(date)).orElseThrow(SheetNotFoundException::new);
    }

    /** The sheet's terms in force on its today. */
    @Transactional(readOnly = true)
    public int terms(String sheetId) {
        return sheets.findById(sheetId).map(s -> s.termsOn(s.getToday())).orElseThrow(SheetNotFoundException::new);
    }

    /** The terms in force on the sheet's today for each grade that has its own then, by grade. */
    @Transactional(readOnly = true)
    public Map<String, Integer> gradeTerms(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        Map<String, Integer> byGrade = new TreeMap<>();
        for (String grade : GRADES) {
            Integer terms = sheet.gradeTermsOn(grade, sheet.getToday());
            if (terms != null) byGrade.put(grade, terms);
        }
        return byGrade;
    }

    /** The terms a claim of the grade dated on the date is given when added: the grade's own then, else the sheet's then. */
    @Transactional(readOnly = true)
    public int termsFor(String sheetId, String grade, LocalDate date) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).termsFor(grade, date);
    }

    /** The late percent in force on the sheet's today. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String sheetId) {
        return sheets.findById(sheetId).map(s -> s.latePctOn(s.getToday())).orElseThrow(SheetNotFoundException::new);
    }

    /** The late percent in force on the date. */
    @Transactional(readOnly = true)
    public BigDecimal latePctOn(String sheetId, LocalDate date) {
        return sheets.findById(sheetId).map(s -> s.latePctOn(date)).orElseThrow(SheetNotFoundException::new);
    }

    /** The values recorded for the sheet's dated settings, by key and then from-date. */
    @Transactional(readOnly = true)
    public List<DatedSetting> dated(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getDated).orElseThrow(SheetNotFoundException::new);
    }

    /** The policy date as set; null when blank, which means the sheet's today. */
    @Transactional(readOnly = true)
    public LocalDate policyDate(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getPolicyDate();
    }

    /** The period numbering from as set; null when blank, which means off. */
    @Transactional(readOnly = true)
    public LocalDate periodNumberingFrom(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getPeriodNumberingFrom();
    }

    /** The cap in force on the sheet's today; null means none. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getCap();
    }

    /** The proof threshold in force on the date; null means none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThresholdOn(String sheetId, LocalDate date) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).proofThresholdOn(date);
    }

    /** The roles set for each action of the permission matrix, comma separated, by action; an action not in it keeps the standing rule. */
    @Transactional(readOnly = true)
    public Map<String, String> permissions(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getPermissions).orElseThrow(SheetNotFoundException::new);
    }

    /** Whether the user's role on the sheet may take the action: by the roles set for it, else its standing rule. */
    @Transactional(readOnly = true)
    public boolean mayDo(String sheetId, String user, String action) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        Role role = roleOf(sheetId, user);
        if (role == Role.VIEWER) return false;
        String set = sheet.permission(action);
        if (set == null) return STANDING_PERMISSIONS.get(action).contains(role);
        return parseRoles(set).contains(role);
    }

    /** Roles written comma separated (clerk, steward), in any case and with spaces around each. */
    public static Set<Role> parseRoles(String roles) {
        Set<Role> parsed = EnumSet.noneOf(Role.class);
        for (String r : roles.split(",")) parsed.add(Role.valueOf(r.strip().toUpperCase()));
        return parsed;
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

    /**
     * Record the clerk limit (null: none) from the date (null: the sheet's today); a limit already
     * recorded from that date is replaced.
     */
    @Transactional
    public BigDecimal setClerkLimit(String sheetId, String user, BigDecimal limit, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        record(sheet, user, Sheet.CLERK_LIMIT, limit == null ? null : limit.toPlainString(), from);
        return limit;
    }

    /** Record the terms from the date (null: the sheet's today); no figure, nor any claim's due-by, moves with them. */
    @Transactional
    public int setTerms(String sheetId, String user, int terms, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        record(sheet, user, Sheet.TERMS, String.valueOf(terms), from);
        return terms;
    }

    /** Record the grade's terms from the date (null: the sheet's today); no claim's due-by moves with them. */
    @Transactional
    public int setGradeTerms(String sheetId, String user, String grade, int terms, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        record(sheet, user, Sheet.TERMS + "-" + grade, String.valueOf(terms), from);
        return terms;
    }

    /** Record the floor from the date (null: the sheet's today); the live dues it moves are audited after its own line. */
    @Transactional
    public BigDecimal setFloor(String sheetId, String user, BigDecimal floor, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        record(sheet, user, Sheet.FLOOR, floor.toPlainString(), from);
        rollup.recordChanges(sheetId, user, before);
        return floor;
    }

    /** Record the late percent from the date (null: the sheet's today); the dues it moves are audited after its own line. */
    @Transactional
    public BigDecimal setLatePct(String sheetId, String user, BigDecimal latePct, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        record(sheet, user, Sheet.LATE_PCT, latePct.toPlainString(), from);
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

    /** Change the period numbering from (null: off); a claim already numbered keeps its number. */
    @Transactional
    public LocalDate setPeriodNumberingFrom(String sheetId, String user, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setPeriodNumberingFrom(from);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=period-numbering-from value="
                + (from == null ? "" : from.toString()));
        return from;
    }

    /** Change the grace days (null: off); no period closes until the sheet's today is next changed. */
    @Transactional
    public Integer setGraceDays(String sheetId, String user, Integer days) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setGraceDays(days);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=grace-days value="
                + (days == null ? "" : days.toString()));
        return days;
    }

    /** The grace days as set; null when blank, which means off. */
    @Transactional(readOnly = true)
    public Integer graceDays(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getGraceDays();
    }

    /** Record the cap (null: none) from the date (null: the sheet's today); the due-totals it moves are audited after its own line. */
    @Transactional
    public BigDecimal setCap(String sheetId, String user, BigDecimal cap, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        record(sheet, user, Sheet.CAP, cap == null ? null : cap.toPlainString(), from);
        rollup.recordChanges(sheetId, user, before);
        return cap;
    }

    /** Record the proof threshold (null: none) from the date (null: the sheet's today); no figure moves with it. */
    @Transactional
    public BigDecimal setProofThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        record(sheet, user, Sheet.PROOF_THRESHOLD, threshold == null ? null : threshold.toPlainString(), from);
        return threshold;
    }

    /** Set the roles (comma separated; blank or null: the standing rule) that may take the action; not dated. */
    @Transactional
    public String setPermission(String sheetId, String user, String action, String roles) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        String value = roles == null ? "" : roles;
        sheet.setPermission(action, value);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=perm-" + action
                + " value=" + value);
        return value;
    }

    /**
     * Record a dated setting's value from the date given (null: the sheet's today) and audit it, with
     * the from-date at its end when one was given.
     */
    private void record(Sheet sheet, String user, String key, String value, LocalDate from) {
        sheet.record(key, from != null ? from : sheet.getToday(), value);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheet.getId() + " by=" + user.strip() + " key=" + key
                + " value=" + (value == null ? "" : value) + (from == null ? "" : " from=" + from));
    }
}
