package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.DatedSetting;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetParent;
import net.officefloor.hq.app.sheet.SheetParentRefusedException;
import net.officefloor.hq.app.sheet.SheetParentRepository;
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
 * and the period numbering from (none: off), from which a claim settled is numbered within its period;
 * and the cap (none: no cap), the most a person's due-total can be; and the proof threshold (none: none,
 * dated, taken on a claim's date; a grade's own, when set, overrides it for a claim whose person has that grade on its date), above which a claim's due needs a proof to be settled or approved;
 * and the entry date (none: off, not dated), the date a new claim given none takes rather than the sheet's today;
 * and the grace days (none: off, not dated), past which an open period closes when the sheet's today changes;
 * and the grace days of each grade (none: none, dated, taken on a claim's date for its person's grade then),
 * past its due-by plus which a claim is overdue and from which its late charge's 30-day boundaries count;
 * and the permission matrix (not dated): for each action, the roles that may take it, blank keeping
 * its standing rule; and the parent (none: none, not dated), the sheet whose rates, then its parent's,
 * up to three levels up, a grade with no rate in force on a date takes.
 */
@Service
public class SettingService {

    /** The grades every sheet has, each of which can have its own terms; a sheet can add more. */
    public static final List<String> GRADES = Sheet.STANDING_GRADES;

    /** The actions of the permission matrix, each with its standing rule: the roles that may take it while none are set. */
    public static final Map<String, Set<Role>> STANDING_PERMISSIONS;
    static {
        Map<String, Set<Role>> standing = new LinkedHashMap<>();
        for (String action : List.of("open", "settle", "void", "close")) standing.put(action, EnumSet.of(Role.CLERK, Role.STEWARD));
        for (String action : List.of("reassign", "reopen")) standing.put(action, EnumSet.of(Role.STEWARD));
        // changing the sheet's today: anyone but a viewer
        standing.put("today", EnumSet.of(Role.CLERK, Role.STEWARD));
        STANDING_PERMISSIONS = Map.copyOf(standing);
    }

    private final SheetRepository sheets;
    private final SheetRoleRepository roles;
    private final DelegationRepository delegations;
    private final SheetParentRepository parents;
    private final RollupService rollup;
    private final Audit audit;

    public SettingService(SheetRepository sheets, SheetRoleRepository roles, DelegationRepository delegations,
            SheetParentRepository parents, RollupService rollup, Audit audit) {
        this.sheets = sheets;
        this.parents = parents;
        this.roles = roles;
        this.delegations = delegations;
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

    /** The clerk limit in force on the sheet's today for a claim of the grade: the grade's own, else the sheet's; null means no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimitFor(String sheetId, String grade) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).clerkLimitFor(grade);
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
        for (String grade : sheet.getGrades()) {
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

    /** The grace days of the grade in force on the date; 0 when none. */
    @Transactional(readOnly = true)
    public int graceOn(String sheetId, String grade, LocalDate date) {
        return sheets.findById(sheetId).map(s -> s.graceOn(grade, date)).orElseThrow(SheetNotFoundException::new);
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

    /** The proof threshold for a claim of the grade dated on the date: the grade's own then, else the sheet's; null means none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThresholdFor(String sheetId, String grade, LocalDate date) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).proofThresholdFor(grade, date);
    }

    /** The note threshold in force on the date; null means none. */
    @Transactional(readOnly = true)
    public BigDecimal noteThresholdOn(String sheetId, LocalDate date) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).noteThresholdOn(date);
    }

    /** The review threshold in force on the date; null means none. */
    @Transactional(readOnly = true)
    public BigDecimal reviewThresholdOn(String sheetId, LocalDate date) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).reviewThresholdOn(date);
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
        if (role == Role.RESTRICTED) role = Role.CLERK;
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

    /**
     * The role the user acts in on the sheet: steward while a steward's delegation to them covers the
     * sheet's today, else their own.
     */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String userName) {
        Role own = ownRoleOf(sheetId, userName);
        if (own != Role.STEWARD && actingFor(sheetId, userName) != null) return Role.STEWARD;
        return own;
    }

    /** The role the user was given on the sheet: clerk unless given another. */
    @Transactional(readOnly = true)
    public Role ownRoleOf(String sheetId, String userName) {
        return roles.findBySheetIdAndUserName(sheetId, userName.strip()).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /**
     * The steward a user who is not one acts for on the sheet: the delegator of the latest delegation to
     * them that covers the sheet's today; null when none does.
     */
    @Transactional(readOnly = true)
    public String actingFor(String sheetId, String userName) {
        String name = userName.strip();
        List<Delegation> to = delegations.findBySheetIdAndUserNameOrderByIdDesc(sheetId, name);
        if (to.isEmpty()) return null;
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        if (today == null || ownRoleOf(sheetId, name) == Role.STEWARD) return null;
        return to.stream().filter(d -> d.covers(today)).map(Delegation::getDelegator).findFirst().orElse(null);
    }

    /** The delegations made on the sheet, in the order they were made. */
    @Transactional(readOnly = true)
    public List<Delegation> delegations(String sheetId) {
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /**
     * A steward (by their own role, not by a delegation) delegates the steward role to the user from
     * the from-date (null: the sheet's today) to the to-date, inclusive; refused for anyone else, or a
     * to-date before the from-date.
     */
    @Transactional
    public Delegation delegate(String sheetId, String user, String userName, LocalDate from, LocalDate to) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (ownRoleOf(sheetId, user) != Role.STEWARD) throw new DelegationRefusedException();
        LocalDate fromDate = from != null ? from : sheet.getToday();
        if (to == null || to.isBefore(fromDate)) throw new DelegationRefusedException();
        String name = userName.strip();
        Delegation saved = delegations.saveAndFlush(new Delegation(sheetId, name, user.strip(), fromDate, to));
        audit.recordOnCommit("DELEGATED sheet=" + sheetId + " by=" + user.strip() + " user=" + name
                + " from=" + fromDate + " to=" + to);
        return saved;
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

    /**
     * Record the grade's clerk limit (null: none, the sheet's applies) from the date (null: the sheet's
     * today); a limit already recorded from that date is replaced.
     */
    @Transactional
    public BigDecimal setGradeClerkLimit(String sheetId, String user, String grade, BigDecimal limit, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        record(sheet, user, Sheet.CLERK_LIMIT + "-" + grade, limit == null ? null : limit.toPlainString(), from);
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

    /**
     * Record the rounding mode (half-up or half-even; null: none, the policy date decides) from the date (null: the
     * sheet's today); the live dues it moves are audited after its own line.
     */
    @Transactional
    public String setRounding(String sheetId, String user, String mode, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        record(sheet, user, Sheet.ROUNDING, mode, from);
        rollup.recordChanges(sheetId, user, before);
        return mode;
    }

    /** The rounding mode in force on the date; null when none. */
    @Transactional(readOnly = true)
    public RoundingMode roundingOn(String sheetId, LocalDate date) {
        // not mapped through the Optional: a null mode (none) would read as no sheet
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).roundingOn(date);
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

    /**
     * Record the grade's grace days (null: none) from the date (null: the sheet's today); the dues and
     * overdue-totals they move are audited after its own line. No claim's due-by moves with them.
     */
    @Transactional
    public Integer setGradeGrace(String sheetId, String user, String grade, Integer days, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        record(sheet, user, Sheet.GRACE + "-" + grade, days == null ? null : days.toString(), from);
        rollup.recordChanges(sheetId, user, before);
        return days;
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

    /** Change the score mid threshold (null: blank); no stored figure changes with it. */
    @Transactional
    public Integer setScoreMid(String sheetId, String user, Integer score) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setScoreMid(score);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=score-mid value="
                + (score == null ? "" : score.toString()));
        return score;
    }

    /** Change the score high threshold (null: blank); no stored figure changes with it. */
    @Transactional
    public Integer setScoreHigh(String sheetId, String user, Integer score) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setScoreHigh(score);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=score-high value="
                + (score == null ? "" : score.toString()));
        return score;
    }

    /** Switch the score log on or off (blank); no stored figure changes with it. */
    @Transactional
    public boolean setScoreLog(String sheetId, String user, boolean on) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setScoreLog(on);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=score-log value="
                + (on ? "on" : ""));
        return on;
    }

    /** Change the escalation gap in days (null: off). */
    @Transactional
    public Integer setEscalateAfter(String sheetId, String user, Integer days) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setEscalateAfter(days);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=escalate-after value="
                + (days == null ? "" : days.toString()));
        return days;
    }

    /** Change the review SLA in days (null: none); no claim changes with it. */
    @Transactional
    public Integer setReviewSla(String sheetId, String user, Integer days) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setReviewSla(days);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=review-sla value="
                + (days == null ? "" : days.toString()));
        return days;
    }

    /** Change the pending SLA in days (null: none); no claim changes with it. */
    @Transactional
    public Integer setPendingSla(String sheetId, String user, Integer days) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setPendingSla(days);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=pending-sla value="
                + (days == null ? "" : days.toString()));
        return days;
    }

    /** Change the maximum of OPEN or PENDING claims per person (null: none); no claim changes with it. */
    @Transactional
    public Integer setMaxOpen(String sheetId, String user, Integer max) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setMaxOpen(max);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=max-open value="
                + (max == null ? "" : max.toString()));
        return max;
    }

    /** Change the fiscal year start month, 1 to 12; the periods' fiscal years follow it. */
    @Transactional
    public int setFiscalStartMonth(String sheetId, String user, int month) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setFiscalStartMonth(month);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=fiscal-start-month value="
                + month);
        return month;
    }

    /** Change the entry date (null: off); no claim already added moves with it. */
    @Transactional
    public LocalDate setEntryDate(String sheetId, String user, LocalDate entryDate) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setEntryDate(entryDate);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=entry-date value="
                + (entryDate == null ? "" : entryDate.toString()));
        return entryDate;
    }

    /** The entry date as set; null when blank, which means off. */
    @Transactional(readOnly = true)
    public LocalDate entryDate(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getEntryDate();
    }

    /** Change the pot (null: none); not dated. */
    @Transactional
    public BigDecimal setPot(String sheetId, String user, BigDecimal pot) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setPot(pot);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=pot value="
                + (pot == null ? "" : pot.toPlainString()));
        // the cuts it moves move the capped due-totals
        rollup.recordChanges(sheetId, user, before);
        return pot;
    }

    /** Change the alert threshold (null: off); not dated. */
    @Transactional
    public BigDecimal setAlertThreshold(String sheetId, String user, BigDecimal threshold) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setAlertThreshold(threshold);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=alert-threshold value="
                + (threshold == null ? "" : threshold.toPlainString()));
        return threshold;
    }

    /** Change the proof kinds (null: none), comma separated; not dated. */
    @Transactional
    public String setProofKinds(String sheetId, String user, String kinds) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setProofKinds(kinds);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=proof-kinds value="
                + (kinds == null ? "" : kinds));
        return kinds;
    }

    /** Change the default grade (null: A); not dated. */
    @Transactional
    public String setDefaultGrade(String sheetId, String user, String grade) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (grade != null && !sheet.hasGrade(grade)) throw new GradeAddRefusedException();
        sheet.setDefaultGrade(grade);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=default-grade value="
                + (grade == null ? "" : grade));
        return grade;
    }

    /** The default grade as set; null when blank, which means A. */
    @Transactional(readOnly = true)
    public String defaultGrade(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getDefaultGrade).orElse(null);
    }

    /** The grade a new person given none takes: the default grade, else A; A too while the default grade is retired. */
    @Transactional(readOnly = true)
    public String gradeForNew(String sheetId) {
        String grade = defaultGrade(sheetId);
        return grade == null || isGradeRetired(sheetId, grade) ? "A" : grade;
    }

    /** Retire the grade (not dated); refused when it is retired already. People on it keep it. */
    @Transactional
    public void retireGrade(String sheetId, String user, String grade) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (sheet.isGradeRetired(grade)) throw new GradeRetiredException();
        sheet.retireGrade(grade);
        sheets.flush();
        audit.recordOnCommit("GRADE_RETIRED sheet=" + sheetId + " by=" + user.strip() + " grade=" + grade);
    }

    /**
     * Add a grade (not dated): a code of one to three capital letters, given with spaces around it or
     * not, that is not a grade of the sheet yet; anything else is refused.
     */
    @Transactional
    public String addGrade(String sheetId, String user, String code) {
        String grade = code == null ? "" : code.strip();
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (!grade.matches("[A-Z]{1,3}") || sheet.hasGrade(grade)) throw new GradeAddRefusedException();
        sheet.addGrade(grade);
        sheets.flush();
        audit.recordOnCommit("GRADE_ADDED sheet=" + sheetId + " by=" + user.strip() + " grade=" + grade);
        return grade;
    }

    /** The sheet's grades, in order: the standing ones, then those added; the standing ones for a sheet never loaded. */
    @Transactional(readOnly = true)
    public List<String> grades(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getGrades).orElse(GRADES);
    }

    /** The grades retired on the sheet, in grade order. */
    @Transactional(readOnly = true)
    public Set<String> retiredGrades(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getRetiredGrades).orElse(Set.of());
    }

    /** Whether the grade is retired on the sheet. */
    @Transactional(readOnly = true)
    public boolean isGradeRetired(String sheetId, String grade) {
        return sheets.findById(sheetId).map(s -> s.isGradeRetired(grade)).orElse(false);
    }

    /** Refuses a grade retired on the sheet, or one that is not a grade of the sheet. */
    @Transactional(readOnly = true)
    public void requireGradeNotRetired(String sheetId, String grade) {
        if (!grades(sheetId).contains(grade)) throw new GradeAddRefusedException();
        if (isGradeRetired(sheetId, grade)) throw new GradeRetiredException();
    }

    /** The proof kinds as set, in order; empty when none. */
    @Transactional(readOnly = true)
    public List<String> proofKinds(String sheetId) {
        String kinds = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getProofKinds();
        return kinds == null || kinds.isEmpty() ? List.of() : List.of(kinds.split(","));
    }

    /** The alert threshold as set; null when blank, which means off. */
    @Transactional(readOnly = true)
    public BigDecimal alertThreshold(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getAlertThreshold();
    }

    /** The pot as set; null when blank, which means none. */
    @Transactional(readOnly = true)
    public BigDecimal pot(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getPot();
    }

    /**
     * Change the parent (null: none); not dated. Refused for the sheet itself, a sheet never loaded, or a
     * sheet that has this one as an ancestor. The dues it moves are audited after its own line.
     */
    @Transactional
    public String setParent(String sheetId, String user, String parent) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (parent != null) {
            if (parent.equals(sheetId) || !sheets.existsById(parent)) throw new SheetParentRefusedException();
            // every ancestor of the parent, however far up, so no chain of parents ever loops
            Set<String> seen = new HashSet<>(Set.of(parent));
            for (String at = parents.parentOf(parent); at != null && seen.add(at); at = parents.parentOf(at)) {
                if (at.equals(sheetId)) throw new SheetParentRefusedException();
            }
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        if (parent == null) {
            parents.findById(sheetId).ifPresent(parents::delete);
        } else {
            SheetParent link = parents.findById(sheetId).orElseGet(() -> new SheetParent(sheetId, parent));
            link.setParentSheet(parent);
            parents.save(link);
        }
        parents.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user.strip() + " key=parent value="
                + (parent == null ? "" : parent));
        rollup.recordChanges(sheetId, user, before);
        return parent;
    }

    /** The parent as set; null when none. */
    @Transactional(readOnly = true)
    public String parent(String sheetId) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        return parents.parentOf(sheetId);
    }

    /** The month (1 to 12) a fiscal year starts in; 1 unless set. */
    @Transactional(readOnly = true)
    public int fiscalStartMonth(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getFiscalStartMonth();
    }

    /** The escalation gap in days as set; null when blank, which means off. */
    @Transactional(readOnly = true)
    public Integer escalateAfter(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getEscalateAfter();
    }

    /** The score mid threshold as set; null when blank. */
    @Transactional(readOnly = true)
    public Integer scoreMid(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getScoreMid();
    }

    /** The score high threshold as set; null when blank. */
    @Transactional(readOnly = true)
    public Integer scoreHigh(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getScoreHigh();
    }

    /** Whether the score log is on; false when blank. */
    @Transactional(readOnly = true)
    public boolean scoreLog(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).isScoreLog();
    }

    /** The review SLA in days as set; null when blank, which means none. */
    @Transactional(readOnly = true)
    public Integer reviewSla(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getReviewSla();
    }

    /** The pending SLA in days as set; null when blank, which means none. */
    @Transactional(readOnly = true)
    public Integer pendingSla(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getPendingSla();
    }

    /** The maximum of OPEN or PENDING claims per person as set; null when blank, which means none. */
    @Transactional(readOnly = true)
    public Integer maxOpen(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getMaxOpen();
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

    /** Record the grade's proof threshold (null: none, the sheet's applies) from the date (null: the sheet's today); no figure moves with it. */
    @Transactional
    public BigDecimal setGradeProofThreshold(String sheetId, String user, String grade, BigDecimal threshold, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        record(sheet, user, Sheet.PROOF_THRESHOLD + "-" + grade, threshold == null ? null : threshold.toPlainString(), from);
        return threshold;
    }

    /** Record the note threshold (null: none) from the date (null: the sheet's today); no figure moves with it. */
    @Transactional
    public BigDecimal setNoteThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        record(sheet, user, Sheet.NOTE_THRESHOLD, threshold == null ? null : threshold.toPlainString(), from);
        return threshold;
    }

    /** Record the review threshold (null: none) from the date (null: the sheet's today); no figure moves with it. */
    @Transactional
    public BigDecimal setReviewThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        record(sheet, user, Sheet.REVIEW_THRESHOLD, threshold == null ? null : threshold.toPlainString(), from);
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
