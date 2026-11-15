package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ProofKind;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.DatedSetting;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The sheet's settings (its permission matrix, its clerk limit, floor, terms, terms per grade, late percent, policy date, period numbering from-date, entry date, cap and proof threshold) and the role each user name holds on it. */
@Service
public class SettingsService {

    /** The key the clerk limit is audited under. */
    public static final String CLERK_LIMIT = Sheet.CLERK_LIMIT;
    /** The key a grade's clerk limit is audited under, followed by the grade. */
    public static final String CLERK_LIMIT_OF_GRADE = Sheet.CLERK_LIMIT_OF_GRADE;
    /** The key the floor is audited under. */
    public static final String FLOOR = Sheet.FLOOR;
    /** The key the terms are audited under. */
    public static final String TERMS = Sheet.TERMS;
    /** The key a grade's terms are audited under, followed by the grade. */
    public static final String TERMS_OF_GRADE = Sheet.TERMS_OF_GRADE;
    /** The key a grade's grace days are audited under, followed by the grade. */
    public static final String GRACE_OF_GRADE = Sheet.GRACE_OF_GRADE;
    /** The key the late percent is audited under. */
    public static final String LATE_PCT = Sheet.LATE_PCT;
    /** The key the rounding mode is audited under. */
    public static final String ROUNDING = Sheet.ROUNDING;
    /** The key the policy date is audited under. */
    public static final String POLICY_DATE = "policy-date";

    /** The key the period numbering from-date is audited under. */
    public static final String PERIOD_NUMBERING_FROM = "period-numbering-from";

    /** The key the entry date is audited under. */
    public static final String ENTRY_DATE = "entry-date";

    /** The key the grace days are audited under. */
    public static final String GRACE_DAYS = "grace-days";
    public static final String ESCALATE_AFTER = "escalate-after";

    /** The key the review SLA is audited under. */
    public static final String REVIEW_SLA = "review-sla";
    public static final String MAX_OPEN = "max-open";

    /** The key the mid score threshold is audited under. */
    public static final String SCORE_MID = "score-mid";

    /** The key the high score threshold is audited under. */
    public static final String SCORE_HIGH = "score-high";

    /** The key the score log switch is audited under. */
    public static final String SCORE_LOG = "score-log";

    /** The key the pot is audited under. */
    public static final String POT = "pot";

    /** The key the alert threshold is audited under. */
    public static final String ALERT_THRESHOLD = "alert-threshold";
    /** The key the fiscal year's start month is audited under. */
    public static final String FISCAL_START_MONTH = "fiscal-start-month";

    /** The key the proof kinds are audited under. */
    public static final String PROOF_KINDS = "proof-kinds";

    /** The key the default grade is audited under. */
    public static final String DEFAULT_GRADE = "default-grade";

    /** The grades the default grade may be. */
    private static final List<String> GRADES = List.of("A", "B", "C");

    /** The key the parent sheet is audited under. */
    public static final String PARENT = "parent";

    /** The key the cap is audited under. */
    public static final String CAP = Sheet.CAP;

    /** The key the proof threshold is audited under. */
    public static final String PROOF_THRESHOLD = Sheet.PROOF_THRESHOLD;

    /** The key a grade's proof threshold is audited under, followed by the grade. */
    public static final String PROOF_THRESHOLD_OF_GRADE = Sheet.PROOF_THRESHOLD_OF_GRADE;

    /** The key the note threshold is audited under. */
    public static final String NOTE_THRESHOLD = Sheet.NOTE_THRESHOLD;

    /** The key the review threshold is audited under. */
    public static final String REVIEW_THRESHOLD = Sheet.REVIEW_THRESHOLD;

    /** The key an action's roles in the permission matrix are audited under, followed by the action. */
    public static final String PERMISSION = "perm-";

    private final SheetRoleRepository roles;
    private final SheetPermissionRepository permissions;
    private final SheetDelegationRepository delegations;
    private final SheetHolidayRepository holidays;
    private final SheetVoidReasonRepository voidReasons;
    private final SheetService sheets;
    private final RollupService rollup;
    private final HistoryService history;
    private final Audit audit;
    private final ApplicationEventPublisher events;

    public SettingsService(SheetRoleRepository roles, SheetPermissionRepository permissions,
            SheetDelegationRepository delegations, SheetHolidayRepository holidays, SheetVoidReasonRepository voidReasons,
            SheetService sheets, RollupService rollup, HistoryService history, Audit audit,
            ApplicationEventPublisher events) {
        this.roles = roles;
        this.permissions = permissions;
        this.delegations = delegations;
        this.holidays = holidays;
        this.voidReasons = voidReasons;
        this.sheets = sheets;
        this.rollup = rollup;
        this.history = history;
        this.audit = audit;
        this.events = events;
    }

    /** The sheet's clerk limit in force on its today; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getClerkLimit).orElse(null);
    }

    /** Set the sheet's clerk limit (null: no limit) from a date (null: the sheet's today), the
     *  sheet created first if it was never loaded; the limit in force on the sheet's today applies. */
    @Transactional
    public BigDecimal setClerkLimit(String sheetId, String user, BigDecimal limit, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        BigDecimal scaled = limit == null ? null : limit.setScale(2);
        record(sheet, user, CLERK_LIMIT, scaled == null ? "" : scaled.toPlainString(), from);
        return scaled;
    }

    /** Set a grade's clerk limit (null: none, the grade uses the sheet's) from a date (null: the
     *  sheet's today), the sheet created first if it was never loaded; a claim whose person's grade
     *  on the sheet's today is this one is held to it while it is in force then. */
    @Transactional
    public BigDecimal setGradeClerkLimit(String sheetId, String user, String grade, BigDecimal limit, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        BigDecimal scaled = limit == null ? null : limit.setScale(2);
        record(sheet, user, CLERK_LIMIT_OF_GRADE + grade, scaled == null ? "" : scaled.toPlainString(), from);
        return scaled;
    }

    /** Record a dated setting's value (blank: none) from a date (null: the sheet's today), replacing
     *  one recorded from the same date, and audit it, with the from-date when one was given. */
    private void record(Sheet sheet, String user, String key, String value, LocalDate from) {
        sheet.recordSetting(key, from != null ? from : sheet.getToday(), value.isEmpty() ? null : value);
        sheets.flush();
        history.recordSheet(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheet.getId() + " by=" + user
                + " key=" + key + " value=" + value + (from == null ? "" : " from=" + from));
    }

    /** The sheet's floor: a claim's live due is at least it. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId) {
        return sheets.floor(sheetId);
    }

    /** Set the sheet's floor from a date (null: the sheet's today), the sheet created first if it
     *  was never loaded; every live due of a claim dated from then on to the next from-date (and so
     *  every total) moves with it, while settled, bound and closed figures stay as they are. */
    @Transactional
    public BigDecimal setFloor(String sheetId, String user, BigDecimal floor, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal scaled = floor.setScale(2);
        record(sheet, user, FLOOR, scaled.toPlainString(), from);
        rollup.recordChanges(sheetId, user, before);
        return scaled;
    }

    /** The sheet's terms: a claim is due by its date plus this many days. */
    @Transactional(readOnly = true)
    public int terms(String sheetId) {
        return sheets.terms(sheetId);
    }

    /** Set the sheet's terms in days from a date (null: the sheet's today), the sheet created first
     *  if it was never loaded; they apply to claims created from then on to the next from-date
     *  (by the sheet's today, whatever their date), in a grade without its own terms, while every claim's due-by, fixed when it
     *  was created, stays as it is. */
    @Transactional
    public int setTerms(String sheetId, String user, int terms, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        record(sheet, user, TERMS, String.valueOf(terms), from);
        return terms;
    }

    /** The terms set for a grade; null when it has none of its own (it uses the sheet's terms). */
    @Transactional(readOnly = true)
    public Integer gradeTerms(String sheetId, String grade) {
        return sheets.find(sheetId).map(s -> s.getGradeTerms(grade)).orElse(null);
    }

    /** Set a grade's terms in days (null: none, the grade uses the sheet's terms), the sheet
     *  created first if it was never loaded; like the sheet's terms they apply to claims created
     *  from the from-date (null: the sheet's today) on (by the sheet's today, whatever their date), while every claim's due-by
     *  stays as it is. */
    @Transactional
    public Integer setGradeTerms(String sheetId, String user, String grade, Integer terms, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        record(sheet, user, TERMS_OF_GRADE + grade, terms == null ? "" : String.valueOf(terms), from);
        return terms;
    }

    /** Set a grade's grace days (null: none) from a date (null: the sheet's today), the sheet
     *  created first if it was never loaded; a claim dated from then on to the next from-date whose
     *  person's grade then is this one is overdue only once its due-by plus them is before the
     *  sheet's today, its late charge counted from there, so every live due (and every total) moves
     *  with them while every due-by and settled figure stays as it is. */
    @Transactional
    public Integer setGradeGrace(String sheetId, String user, String grade, Integer grace, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        record(sheet, user, GRACE_OF_GRADE + grade, grace == null ? "" : String.valueOf(grace), from);
        rollup.recordChanges(sheetId, user, before);
        return grace;
    }

    /** The sheet's late percent: an overdue claim's due gains it for every full 30 days overdue. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String sheetId) {
        return sheets.latePct(sheetId);
    }

    /** Set the sheet's late percent from a date (null: the sheet's today), the sheet created first
     *  if it was never loaded; the late charge of every overdue OPEN claim with a 30-day boundary from
     *  then on to the next from-date (and so every total) moves with it, while settled figures stay. */
    @Transactional
    public BigDecimal setLatePct(String sheetId, String user, BigDecimal latePct, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal scaled = latePct.setScale(2);
        record(sheet, user, LATE_PCT, scaled.toPlainString(), from);
        rollup.recordChanges(sheetId, user, before);
        return scaled;
    }

    /** The sheet's rounding mode in force on its today; null when there is none. */
    @Transactional(readOnly = true)
    public String rounding(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getRounding).orElse(null);
    }

    /** Set the sheet's rounding mode (half-up or half-even; null: none) from a date (null: the
     *  sheet's today), the sheet created first if it was never loaded; every live figure of a claim
     *  dated from then on to the next from-date (and so every total) rounds by it, a claim with none
     *  in force on its date by the policy date, while settled, bound and closed figures stay as they are. */
    @Transactional
    public String setRounding(String sheetId, String user, String mode, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        record(sheet, user, ROUNDING, mode == null ? "" : mode, from);
        rollup.recordChanges(sheetId, user, before);
        return mode;
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

    /** The sheet's entry date; null when blank (off, a new claim left undated takes the sheet's today). */
    @Transactional(readOnly = true)
    public LocalDate entryDate(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getEntryDate).orElse(null);
    }

    /** Set the sheet's entry date (null: off), the sheet created first if it was never loaded; not
     *  dated, it is the date a new claim left undated takes from now on, claims already added
     *  staying as they are. */
    @Transactional
    public LocalDate setEntryDate(String sheetId, String user, LocalDate entryDate) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setEntryDate(entryDate);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + ENTRY_DATE + " value=" + (entryDate == null ? "" : entryDate));
        return entryDate;
    }

    /** The sheet's grace days; null when blank (off). */
    @Transactional(readOnly = true)
    public Integer graceDays(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getGraceDays).orElse(null);
    }

    /** Set the sheet's grace days (null: off), the sheet created first if it was never loaded; not
     *  dated, they apply from the next change of the sheet's today. */
    @Transactional
    public Integer setGraceDays(String sheetId, String user, Integer graceDays) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setGraceDays(graceDays);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + GRACE_DAYS + " value=" + (graceDays == null ? "" : graceDays));
        return graceDays;
    }

    /** The sheet's escalation gap in days; null when blank (off). */
    @Transactional(readOnly = true)
    public Integer escalateAfter(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getEscalateAfter).orElse(null);
    }

    /** Set the sheet's escalation gap (null: off), the sheet created first if it was never loaded;
     *  not dated, it applies from the next reminder. */
    @Transactional
    public Integer setEscalateAfter(String sheetId, String user, Integer escalateAfter) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setEscalateAfter(escalateAfter);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + ESCALATE_AFTER + " value=" + (escalateAfter == null ? "" : escalateAfter));
        return escalateAfter;
    }

    /** The sheet's review SLA in days; null when blank (none). */
    @Transactional(readOnly = true)
    public Integer reviewSla(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getReviewSla).orElse(null);
    }

    /** Set the sheet's review SLA (null: none), the sheet created first if it was never loaded;
     *  not dated, it applies to every REVIEW claim at once. */
    @Transactional
    public Integer setReviewSla(String sheetId, String user, Integer reviewSla) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setReviewSla(reviewSla);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + REVIEW_SLA + " value=" + (reviewSla == null ? "" : reviewSla));
        return reviewSla;
    }

    /** The most OPEN or PENDING claims a person on the sheet may have; null when blank (none). */
    @Transactional(readOnly = true)
    public Integer maxOpen(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getMaxOpen).orElse(null);
    }

    /** Set the sheet's maximum of open claims per person (null: none), the sheet created first if
     *  it was never loaded; not dated, it applies to every opening from then on. */
    @Transactional
    public Integer setMaxOpen(String sheetId, String user, Integer maxOpen) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setMaxOpen(maxOpen);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + MAX_OPEN + " value=" + (maxOpen == null ? "" : maxOpen));
        return maxOpen;
    }

    /** The sheet's mid score threshold; null when blank. */
    @Transactional(readOnly = true)
    public Integer scoreMid(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getScoreMid).orElse(null);
    }

    /** Set the sheet's mid score threshold (null: blank), the sheet created first if it was never
     *  loaded; not dated, while the high one is set too every person's band follows it at once. */
    @Transactional
    public Integer setScoreMid(String sheetId, String user, Integer scoreMid) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setScoreMid(scoreMid);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + SCORE_MID + " value=" + (scoreMid == null ? "" : scoreMid));
        return scoreMid;
    }

    /** The sheet's high score threshold; null when blank. */
    @Transactional(readOnly = true)
    public Integer scoreHigh(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getScoreHigh).orElse(null);
    }

    /** Set the sheet's high score threshold (null: blank), the sheet created first if it was never
     *  loaded; not dated, while the mid one is set too every person's band follows it at once. */
    @Transactional
    public Integer setScoreHigh(String sheetId, String user, Integer scoreHigh) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setScoreHigh(scoreHigh);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + SCORE_HIGH + " value=" + (scoreHigh == null ? "" : scoreHigh));
        return scoreHigh;
    }

    /** Whether the sheet's score log switch is on; off unless set. */
    @Transactional(readOnly = true)
    public boolean scoreLog(String sheetId) {
        return sheets.find(sheetId).map(Sheet::isScoreLog).orElse(false);
    }

    /** Set the sheet's score log switch (false: blank), the sheet created first if it was never
     *  loaded; not dated. */
    @Transactional
    public boolean setScoreLog(String sheetId, String user, boolean scoreLog) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setScoreLog(scoreLog);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + SCORE_LOG + " value=" + (scoreLog ? "on" : ""));
        return scoreLog;
    }

    /** The month (1 to 12) the sheet's fiscal year starts in; 1 unless set. */
    @Transactional(readOnly = true)
    public int fiscalStartMonth(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getFiscalStartMonth).orElse(1);
    }

    /** Set the month (1 to 12) the sheet's fiscal year starts in, the sheet created first if it was
     *  never loaded; not dated. */
    @Transactional
    public int setFiscalStartMonth(String sheetId, String user, int month) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setFiscalStartMonth(month);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + FISCAL_START_MONTH + " value=" + month);
        return month;
    }

    /** The sheet's pot; null when blank (none). */
    @Transactional(readOnly = true)
    public BigDecimal pot(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getPot).orElse(null);
    }

    /** Set the sheet's pot (null: none), the sheet created first if it was never loaded; not dated. */
    @Transactional
    public BigDecimal setPot(String sheetId, String user, BigDecimal pot) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        BigDecimal scaled = pot == null ? null : pot.setScale(2);
        sheet.setPot(scaled);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + POT + " value=" + (scaled == null ? "" : scaled.toPlainString()));
        return scaled;
    }

    /** The sheet's alert threshold; null when blank (off). */
    @Transactional(readOnly = true)
    public BigDecimal alertThreshold(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getAlertThreshold).orElse(null);
    }

    /** Set the sheet's alert threshold (null: off), the sheet created first if it was never
     *  loaded; not dated. */
    @Transactional
    public BigDecimal setAlertThreshold(String sheetId, String user, BigDecimal threshold) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        BigDecimal scaled = threshold == null ? null : threshold.setScale(2);
        sheet.setAlertThreshold(scaled);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + ALERT_THRESHOLD + " value=" + (scaled == null ? "" : scaled.toPlainString()));
        return scaled;
    }

    /** Set the sheet's proof kinds (blank: none), the sheet created first if it was never loaded;
     *  not dated. Each is trimmed, blanks and repeats dropped; while any is set, a claim whose due
     *  is above the proof threshold needs a proof of each to be settled or approved. Refused (422)
     *  when one is neither receipt nor photo. Resolves to the kinds as set, comma separated (null: none). */
    @Transactional
    public String setProofKinds(String sheetId, String user, String kinds) {
        List<String> each = Arrays.stream(kinds.split(",")).map(String::trim).filter(k -> !k.isEmpty())
                .distinct().toList();
        for (String k : each) {
            if (ProofKind.of(k).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "A proof kind is receipt or photo, not " + k);
            }
        }
        String value = each.isEmpty() ? null : String.join(",", each);
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setProofKinds(value);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + PROOF_KINDS + " value=" + (value == null ? "" : value));
        return value;
    }

    /** Set the sheet's default grade (blank: A), the sheet created first if it was never loaded; not
     *  dated. A person the quick-add bar adds, and an import line with a blank grade, is given it;
     *  no one already on the sheet changes. Refused (422) when it is not one of the grades. Resolves
     *  to the grade as set (null: blank). */
    @Transactional
    public String setDefaultGrade(String sheetId, String user, String grade) {
        String value = grade.trim();
        if (!value.isEmpty() && !GRADES.contains(value)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The default grade is one of " + String.join(", ", GRADES) + ", not " + value);
        }
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        sheet.setDefaultGrade(value.isEmpty() ? null : value);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + DEFAULT_GRADE + " value=" + value);
        return value.isEmpty() ? null : value;
    }

    /** Retire a grade on the sheet, the sheet created first if it was never loaded; not dated. It
     *  cannot be chosen for a new person or in an edit from then on; the people on it keep it.
     *  Refused (422) when it is not one of the grades, (409) when it is retired already. Resolves to
     *  the sheet's retired grades, in the order retired. */
    @Transactional
    public List<String> retireGrade(String sheetId, String user, String grade) {
        if (!GRADES.contains(grade)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A grade is one of " + String.join(", ", GRADES) + ", not " + grade);
        }
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        if (sheet.isGradeRetired(grade)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Grade " + grade + " is retired already");
        }
        sheet.retireGrade(grade);
        sheets.flush();
        audit.recordOnCommit("GRADE_RETIRED sheet=" + sheetId + " by=" + user + " grade=" + grade);
        return retiredGrades(sheet);
    }

    /** The sheet's retired grades, in the order retired. */
    public static List<String> retiredGrades(Sheet sheet) {
        return sheet.getRetiredGrades() == null ? List.of() : List.of(sheet.getRetiredGrades().split(","));
    }

    /** The id of the sheet's parent; null when blank (none). */
    @Transactional(readOnly = true)
    public String parent(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getParent).orElse(null);
    }

    /** Set the sheet's parent (null: none), the sheet created first if it was never loaded; not
     *  dated, a grade with no rate of its own in force on a date takes the parent's (then its
     *  parent's, up to three levels up), so every live due (and every total) moves with it. Refused
     *  (422) when it is the sheet itself, a sheet never loaded, or one with this sheet as an ancestor. */
    @Transactional
    public String setParent(String sheetId, String user, String parent) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        if (parent != null) {
            if (parent.equals(sheetId)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A sheet cannot be its own parent");
            }
            if (sheets.find(parent).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No sheet " + parent + " has been loaded");
            }
            Set<String> seen = new HashSet<>();
            for (String at = parent; at != null && seen.add(at); at = sheets.find(at).map(Sheet::getParent).orElse(null)) {
                if (at.equals(sheetId)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "The sheet " + parent + " has this sheet as an ancestor");
                }
            }
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setParent(parent);
        sheets.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + PARENT + " value=" + (parent == null ? "" : parent));
        rollup.recordChanges(sheetId, user, before);
        return parent;
    }

    /** The sheet's cap in force on its today; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String sheetId) {
        return sheets.cap(sheetId);
    }

    /** Set the sheet's cap (null: none) from a date (null: the sheet's today), the sheet created
     *  first if it was never loaded; every person's due-total (and so every total) is held to the
     *  cap in force on the sheet's today. */
    @Transactional
    public BigDecimal setCap(String sheetId, String user, BigDecimal cap, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal scaled = cap == null ? null : cap.setScale(2);
        record(sheet, user, CAP, scaled == null ? "" : scaled.toPlainString(), from);
        rollup.recordChanges(sheetId, user, before);
        return scaled;
    }

    /** The sheet's proof threshold in force on its today; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThreshold(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getProofThreshold).orElse(null);
    }

    /** Set the sheet's proof threshold (null: none) from a date (null: the sheet's today), the
     *  sheet created first if it was never loaded; a claim dated from then on to the next from-date
     *  whose due is above it cannot be settled or approved while it has no proof. */
    @Transactional
    public BigDecimal setProofThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        BigDecimal scaled = threshold == null ? null : threshold.setScale(2);
        record(sheet, user, PROOF_THRESHOLD, scaled == null ? "" : scaled.toPlainString(), from);
        return scaled;
    }

    /** Set a grade's proof threshold (null: none, the grade uses the sheet's) from a date (null:
     *  the sheet's today), the sheet created first if it was never loaded; a claim dated from then
     *  on to the next from-date whose person's grade then is this one and whose due is above it
     *  cannot be settled or approved while it has no proof. */
    @Transactional
    public BigDecimal setGradeProofThreshold(String sheetId, String user, String grade, BigDecimal threshold, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        BigDecimal scaled = threshold == null ? null : threshold.setScale(2);
        record(sheet, user, PROOF_THRESHOLD_OF_GRADE + grade, scaled == null ? "" : scaled.toPlainString(), from);
        return scaled;
    }

    /** Set the sheet's note threshold (null: none) from a date (null: the sheet's today), the
     *  sheet created first if it was never loaded; a claim dated from then on to the next from-date
     *  whose due is above it cannot be opened while its note is empty. */
    @Transactional
    public BigDecimal setNoteThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        BigDecimal scaled = threshold == null ? null : threshold.setScale(2);
        record(sheet, user, NOTE_THRESHOLD, scaled == null ? "" : scaled.toPlainString(), from);
        return scaled;
    }

    /** Set the sheet's review threshold (null: none) from a date (null: the sheet's today), the
     *  sheet created first if it was never loaded; a claim dated from then on to the next from-date
     *  whose due is above it goes to REVIEW when opened, for a steward to make OPEN. */
    @Transactional
    public BigDecimal setReviewThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        BigDecimal scaled = threshold == null ? null : threshold.setScale(2);
        record(sheet, user, REVIEW_THRESHOLD, scaled == null ? "" : scaled.toPlainString(), from);
        return scaled;
    }

    /** Every value recorded for the sheet's dated settings, by key, each key's by from-date. */
    @Transactional(readOnly = true)
    public Map<String, List<DatedSetting>> datedSettings(String sheetId) {
        return sheets.find(sheetId).map(Sheet::getDatedSettings).orElse(Map.of());
    }

    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserAsc(sheetId);
    }

    /** The role a user name acts in on the sheet: a steward while a steward's delegation to it
     *  covers the sheet's today, else the role it holds. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        Role held = heldRoleOf(sheetId, user);
        return held != Role.STEWARD && delegatorOf(sheetId, user) != null ? Role.STEWARD : held;
    }

    /** The role a user name holds on the sheet in its own right: a clerk unless set otherwise. */
    @Transactional(readOnly = true)
    public Role heldRoleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** The steward whose delegation the user name acts under on the sheet's today; null when it
     *  acts in its own right (a steward itself, or no delegation covers the sheet's today). */
    @Transactional(readOnly = true)
    public String delegatorOf(String sheetId, String user) {
        List<SheetDelegation> made = delegations.findBySheetIdAndUserOrderByIdDesc(sheetId, user);
        if (made.isEmpty() || heldRoleOf(sheetId, user) == Role.STEWARD) {
            return null;
        }
        LocalDate today = sheets.today(sheetId);
        return made.stream().filter(d -> d.covers(today)).map(SheetDelegation::getSteward).findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<SheetDelegation> delegations(String sheetId) {
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Delegate the steward role to a user name from a date (null: the sheet's today) to a date
     *  (null: the from-date), inclusive, the sheet created first if it was never loaded; refused
     *  (403) unless the user is a steward in its own right, and (400) for a to-date before the
     *  from-date. */
    @Transactional
    public SheetDelegation delegate(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.load(sheetId, user);
        if (heldRoleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may delegate");
        }
        LocalDate start = from == null ? sheets.today(sheetId) : from;
        LocalDate end = to == null ? start : to;
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date");
        }
        SheetDelegation made = delegations.saveAndFlush(new SheetDelegation(sheetId, name, user, start, end));
        audit.recordOnCommit("DELEGATED sheet=" + sheetId + " by=" + user + " user=" + name
                + " from=" + start + " to=" + end);
        return made;
    }

    /** The sheet's holidays, earliest first. */
    @Transactional(readOnly = true)
    public List<LocalDate> holidays(String sheetId) {
        return holidays.findBySheetIdOrderByDayAsc(sheetId).stream().map(SheetHoliday::getDay).toList();
    }

    /** Add a holiday to the sheet, the sheet created first if it was never loaded; refused (409)
     *  when the date is on its list already. {@link HolidayAdded} is published once it is on the
     *  list, re-fixing the due-by of the claims due on or after it. */
    @Transactional
    public LocalDate addHoliday(String sheetId, String user, LocalDate day) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        if (holidays.existsBySheetIdAndDay(sheetId, day)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The date is a holiday already");
        }
        holidays.saveAndFlush(new SheetHoliday(sheetId, day));
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + day);
        events.publishEvent(new HolidayAdded(sheetId, user, day));
        return day;
    }

    /** The sheet's void reasons, in the order they were added. */
    @Transactional(readOnly = true)
    public List<String> voidReasons(String sheetId) {
        return voidReasons.findBySheetIdOrderByIdAsc(sheetId).stream().map(SheetVoidReason::getReason).toList();
    }

    /** Whether the reason is on the sheet's list of void reasons. */
    @Transactional(readOnly = true)
    public boolean isVoidReason(String sheetId, String reason) {
        return voidReasons.existsBySheetIdAndReason(sheetId, reason);
    }

    /** Add a void reason to the sheet, the sheet created first if it was never loaded; refused (409)
     *  when the reason is on its list already. */
    @Transactional
    public String addVoidReason(String sheetId, String user, String reason) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        if (voidReasons.existsBySheetIdAndReason(sheetId, reason)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The reason is on the list already");
        }
        voidReasons.saveAndFlush(new SheetVoidReason(sheetId, reason));
        audit.recordOnCommit("VOID_REASON_ADDED sheet=" + sheetId + " by=" + user + " reason=" + reason);
        return reason;
    }

    /** Archive the sheet, the sheet created first if it was never loaded; refused (403) unless the
     *  user acts as a steward, and (409) when it is archived already. */
    @Transactional
    public Sheet archive(String sheetId, String user) {
        Sheet sheet = sheets.load(sheetId, user);
        sheets.lock(sheetId);
        if (roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may archive the sheet");
        }
        if (sheet.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet is archived already");
        }
        sheet.archive();
        sheets.flush();
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + sheetId + " by=" + user);
        return sheet;
    }

    /** Whether the user name is a viewer on the sheet: sees it and may change nothing on it. */
    @Transactional(readOnly = true)
    public boolean isViewer(String sheetId, String user) {
        return roleOf(sheetId, user) == Role.VIEWER;
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

    /** Whether a user may settle a claim showing a due (null: n/a, within any limit) whose person
     *  has a grade on the sheet's today: a steward settles anything, a clerk only a due at or below
     *  the clerk limit in force (the grade's own when one is set, else the sheet's), if there is one. */
    @Transactional(readOnly = true)
    public boolean maySettle(String sheetId, String user, String grade, BigDecimal due) {
        if (due == null || roleOf(sheetId, user) == Role.STEWARD) {
            return true;
        }
        BigDecimal limit = sheets.find(sheetId).map(s -> s.clerkLimitFor(grade)).orElse(null);
        return limit == null || due.compareTo(limit) <= 0;
    }

    /** The roles the sheet's permission matrix sets for each action, as set; an action left blank
     *  (keeping its standing rule) is left out. */
    @Transactional(readOnly = true)
    public Map<Permission, String> permissions(String sheetId) {
        Map<Permission, String> out = new EnumMap<>(Permission.class);
        permissions.findBySheetId(sheetId).forEach(p -> out.put(p.getAction(), p.getRoles()));
        return out;
    }

    /** Set the roles that may take an action (blank: its standing rule), comma separated, the
     *  sheet created first if it was never loaded; not dated, it applies from now on. */
    @Transactional
    public String setPermission(String sheetId, String user, Permission action, String rolesText) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        SheetPermission held = permissions.findBySheetIdAndAction(sheetId, action.key()).orElse(null);
        if (rolesText.isEmpty()) {
            if (held != null) {
                permissions.delete(held);
            }
        } else if (held == null) {
            permissions.save(new SheetPermission(sheetId, action, rolesText));
        } else {
            held.setRoles(rolesText);
        }
        permissions.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                + " key=" + PERMISSION + action.key() + " value=" + rolesText);
        return rolesText;
    }

    /** Whether the user's role may take the action: as the permission matrix sets it, else by its
     *  standing rule; a restricted user takes it as a clerk. */
    @Transactional(readOnly = true)
    public boolean mayTake(String sheetId, String user, Permission action) {
        Role acting = roleOf(sheetId, user);
        Role role = acting == Role.RESTRICTED ? Role.CLERK : acting;
        return permissions.findBySheetIdAndAction(sheetId, action.key())
                .map(p -> p.roleSet().contains(role))
                .orElseGet(() -> action.standing().contains(role));
    }

    /** Refuse (403) the action unless the user's role may take it. */
    @Transactional(readOnly = true)
    public void refuseUnlessAllowed(String sheetId, String user, Permission action) {
        if (!mayTake(sheetId, user, action)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user's role may not " + action.key() + " on this sheet");
        }
    }
}
