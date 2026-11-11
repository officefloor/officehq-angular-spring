package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's settings: its clerk limit (none: no limit) and its floor, the least a claim's live due
 * can be, and its terms, the days after its date a claim is due by, and its late percent, what an
 * overdue claim gains of its figure for every full 30 days overdue. Changing the floor or the late
 * percent moves the figures, so the figures that move are recorded. Its policy date (none:
 * the sheet's today) decides how a claim's live figures round (see {@link Rounding}), so changing it
 * records the figures that move too. Its period-numbering-from date (none: off) decides how a claim
 * settled is numbered: on or after it, within its period (see {@link SheetService#nextClaimNumber}).
 * Its cap (none: no cap) is the most a person's due-total can be, so changing it records the
 * figures that move too. A grade may have its own terms; a grade without uses the sheet's terms. A claim's due-by is fixed
 * when it is created, so changing any terms moves no claim's due-by. A grade may have grace days (none: no grace): a
 * claim whose person's grade on its date has grace days on that date is overdue only once its due-by plus them is
 * before the sheet's today, and its late charge counts its 30-day boundaries from there (see
 * {@link Sheet#lateFrom}); its due-by is unchanged. Changing a grade's grace days moves the figures, so the figures
 * that move are recorded.
 * <p>
 * The settings that carry a figure (the floor, the late percent, the terms, a grade's own terms,
 * a grade's grace days, the clerk limit, the cap, the proof threshold, a grade's own proof threshold and the note threshold) are dated: each value is recorded from a from-date (the sheet's
 * today when none is given). A claim's floor and late percent are those in force on its
 * date (its terms are those in force on the day it was created: the sheet's today then), as is its proof threshold (its person's grade's own in force then when it has one, else the sheet's; see {@link Sheet#proofThresholdFor}) and its note threshold (see {@link Sheet#noteThresholdOn}) and its review threshold (see {@link Sheet#reviewThresholdOn}); the clerk limit and the cap are those in force on the sheet's today (see {@link Sheet}).
 * The policy date and the period-numbering-from date are not dated. Nor is the permission matrix:
 * for each of open, settle, void, reassign, close and reopen, the roles that may take it (blank: the
 * standing rule, see {@link Sheet#permits}), recorded under the key {@code perm-<action>}. Nor are
 * the grace days (none: off): when the sheet's today changes, every open period whose to-date plus
 * the grace days is before the new today is closed (see {@link SheetService#setToday}). Nor is the
 * entry date (none: off): while set, it is the default date of a new claim in place of the sheet's today.
 * Nor is the pot (none: no pot), shared out over each balanced pool's members as their cuts (see
 * {@link net.officefloor.hq.app.pool.PoolService}). Nor is the parent (none: no parent), a sheet that has
 * been loaded, never the sheet itself nor one that has it as an ancestor: a grade with no rate in force on a date
 * here takes its parent's, then the parent's parent's, up to three levels up (see {@link RateChain}), so changing it
 * records the figures that move too. Nor are the proof kinds (none: off): while set, a claim above its proof threshold needs a proof of each
 * before it can be settled or approved. Nor is the default grade (none: A), the grade
 * the quick-add bar gives a new person and an import line with a blank grade takes; changing it changes no one. Nor is the alert threshold (none: off): a change whose figures
 * lift a person's due-total above it records an ALERT (see {@link RollupService#recordChanges}); changing
 * it moves no figures. The rounding mode (none: off) is dated like the floor: a claim whose date has
 * one in force rounds its live figures by it in place of the policy date (see {@link Rounding}), so
 * changing it records the figures that move too.
 */
@Service
public class SettingService {

    public static final String CLERK_LIMIT = Sheet.CLERK_LIMIT;
    public static final String GRADE_CLERK_LIMIT = Sheet.GRADE_CLERK_LIMIT;
    public static final String FLOOR = Sheet.FLOOR;
    public static final String CAP = Sheet.CAP;
    public static final String TERMS = Sheet.TERMS;
    /** The key of a grade's own terms: {@code terms-<grade>}. */
    public static final String GRADE_TERMS = Sheet.GRADE_TERMS;
    /** The key of a grade's grace days: {@code grace-<grade>}. */
    public static final String GRADE_GRACE = Sheet.GRADE_GRACE;
    public static final String LATE_PCT = Sheet.LATE_PCT;
    public static final String PROOF_THRESHOLD = Sheet.PROOF_THRESHOLD;
    public static final String NOTE_THRESHOLD = Sheet.NOTE_THRESHOLD;
    public static final String REVIEW_THRESHOLD = Sheet.REVIEW_THRESHOLD;
    /** The key of a grade's own proof threshold: {@code proof-threshold-<grade>}. */
    public static final String GRADE_PROOF_THRESHOLD = Sheet.GRADE_PROOF_THRESHOLD;
    public static final String ROUNDING = Sheet.ROUNDING;
    public static final String POLICY_DATE = "policy-date";
    public static final String PERIOD_NUMBERING_FROM = "period-numbering-from";
    public static final String GRACE_DAYS = "grace-days";
    public static final String ENTRY_DATE = "entry-date";
    public static final String POT = "pot";
    public static final String PARENT = "parent";
    public static final String FISCAL_START_MONTH = "fiscal-start-month";
    public static final String ALERT_THRESHOLD = "alert-threshold";
    public static final String ESCALATE_AFTER = "escalate-after";
    public static final String SCORE_MID = "score-mid";
    public static final String SCORE_HIGH = "score-high";
    public static final String REVIEW_SLA = "review-sla";
    public static final String PROOF_KINDS = "proof-kinds";
    public static final String DEFAULT_GRADE = "default-grade";
    /** The key of a permission matrix entry: {@code perm-<action>}. */
    public static final String PERMISSION = "perm-";

    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final RollupService rollup;
    private final Audit audit;
    private final RoleService roles;
    private final RateChain rateChain;

    public SettingService(SheetService sheets, SheetRepository sheetStore, RollupService rollup, Audit audit,
            RoleService roles, RateChain rateChain) {
        this.rateChain = rateChain;
        this.roles = roles;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional
    public Sheet get(String sheetId, String user) {
        return sheets.load(sheetId, user);
    }

    /**
     * A value to record for a setting that carries a figure (null for none: no limit, no cap) from
     * a from-date (null: from the sheet's today, and the record names no from-date).
     */
    public record Dated<T>(T value, LocalDate from) {
    }

    /**
     * Save the settings given: a clerk limit when {@code limit} is not null, and each grade's own clerk
     * limit given in {@code gradeClerkLimits} (by grade, in grade order; null for none), and the floor, the
     * terms and the late percent when they are not null, and a policy date when {@code changePolicy}
     * (null for none), and a period-numbering-from date when {@code changeNumbering} (null for off),
     * and a cap when {@code cap} is not null, and each grade's own terms given in {@code gradeTerms}
     * (by grade, in grade order), and each grade's grace days given in {@code gradeGraces} (by grade, in grade
     * order; null for none), and a proof threshold when {@code proofThreshold} is not null, and each grade's own proof
     * threshold given in {@code gradeProofThresholds} (by grade, in grade order; null for none), and a note threshold when
     * {@code noteThreshold} is not null, and a review threshold when {@code reviewThreshold} is not null, and each permission matrix entry
     * given in {@code permissions} (by action, in the order given; blank for the standing rule), and the grace days when
     * {@code changeGrace} (null for off), and the entry date when {@code changeEntry} (null for off), and the pot when {@code changePot} (null for none), and the parent when
     * {@code changeParent} (null for none; refused (400) for the sheet itself, a sheet never loaded, or one that has
     * this sheet as an ancestor, before anything is saved), and the fiscal start month when {@code fiscalStartMonth}
     * is not null (1 to 12; anything else refused (400) before anything is saved), and the alert threshold when
     * {@code changeAlert} (null for off), and the rounding mode when {@code rounding} is not null (half-up or
     * half-even, null for none; anything else refused (400) before anything is saved), and the escalation gap when
     * {@code changeEscalate} (null for off), and the review SLA when {@code changeReviewSla} (null for none), and the proof kinds when {@code changeProofKinds} (comma separated, null for none), and the default grade when
     * {@code changeDefaultGrade} (A, B or C, null for none; anything else refused (400) before anything is saved). Each figure is recorded from its from-date, keeping the values
     * already recorded (one already recorded from that date is replaced). Each one saved is
     * recorded, in that order.
     */
    @Transactional
    public Sheet set(String sheetId, String user, Dated<BigDecimal> limit,
            Map<String, Dated<BigDecimal>> gradeClerkLimits, Dated<BigDecimal> floor,
            Dated<Integer> terms, Dated<BigDecimal> latePct, boolean changePolicy, LocalDate policyDate,
            boolean changeNumbering, LocalDate numberingFrom, Dated<BigDecimal> cap,
            Map<String, Dated<Integer>> gradeTerms, Map<String, Dated<Integer>> gradeGraces,
            Dated<BigDecimal> proofThreshold, Map<String, Dated<BigDecimal>> gradeProofThresholds,
            Dated<BigDecimal> noteThreshold, Dated<BigDecimal> reviewThreshold, Map<String, String> permissions,
            boolean changeGrace, Integer graceDays, boolean changeEntry, LocalDate entryDate,
            boolean changePot, BigDecimal pot, boolean changeParent, String parent,
            Integer fiscalStartMonth, boolean changeAlert, BigDecimal alertThreshold, Dated<String> rounding,
            boolean changeEscalate, Integer escalateAfter, boolean changeReviewSla, Integer reviewSla,
            boolean changeProofKinds, String proofKinds, boolean changeDefaultGrade, String defaultGrade,
            boolean changeScoreMid, Integer scoreMid, boolean changeScoreHigh, Integer scoreHigh) {
        roles.refuseViewer(sheetId, user);
        Sheet sheet = sheets.load(sheetId, user);
        if (changeParent && parent != null) {
            if (parent.equals(sheetId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A sheet cannot be its own parent.");
            }
            if (!sheetStore.existsById(parent)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No sheet " + parent + " has been loaded.");
            }
            if (rateChain.wouldLoop(sheetId, parent)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sheet " + parent + " has this sheet as an ancestor.");
            }
        }
        if (rounding != null && rounding.value() != null && !Sheet.ROUNDING_MODES.contains(rounding.value())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The rounding mode is half-up or half-even.");
        }
        if (changeDefaultGrade && defaultGrade != null && !defaultGrade.matches("[ABC]")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The default grade is A, B or C.");
        }
        if (fiscalStartMonth != null && (fiscalStartMonth < 1 || fiscalStartMonth > 12)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The fiscal start month is 1 to 12.");
        }
        if (limit != null) {
            sheet = record(sheet, user, CLERK_LIMIT, limit.value(), limit.from(), text(limit.value()), false);
        }
        for (Map.Entry<String, Dated<BigDecimal>> e : new TreeMap<>(gradeClerkLimits).entrySet()) {
            Dated<BigDecimal> l = e.getValue();
            sheet = record(sheet, user, GRADE_CLERK_LIMIT + e.getKey(), l.value(), l.from(), text(l.value()), false);
        }
        if (floor != null) {
            sheet = record(sheet, user, FLOOR, floor.value(), floor.from(), text(floor.value()), true);
        }
        if (terms != null) {
            sheet = record(sheet, user, TERMS, BigDecimal.valueOf(terms.value()), terms.from(),
                    String.valueOf(terms.value()), false);
        }
        if (latePct != null) {
            sheet = record(sheet, user, LATE_PCT, latePct.value(), latePct.from(), text(latePct.value()), true);
        }
        if (changePolicy) {
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            sheet.setPolicyDate(policyDate);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, POLICY_DATE, policyDate == null ? "" : policyDate.toString()));
            rollup.recordChanges(sheetId, user, before);
        }
        if (rounding != null) {
            sheet = record(sheet, user, ROUNDING, Sheet.roundingCode(rounding.value()), rounding.from(),
                    rounding.value() == null ? "" : rounding.value(), true);
        }
        if (changeNumbering) {
            sheet.setPeriodNumberingFrom(numberingFrom);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, PERIOD_NUMBERING_FROM,
                    numberingFrom == null ? "" : numberingFrom.toString()));
        }
        if (cap != null) {
            sheet = record(sheet, user, CAP, cap.value(), cap.from(), text(cap.value()), true);
        }
        for (Map.Entry<String, Dated<Integer>> e : new TreeMap<>(gradeTerms).entrySet()) {
            Dated<Integer> t = e.getValue();
            sheet = record(sheet, user, GRADE_TERMS + e.getKey(), BigDecimal.valueOf(t.value()), t.from(),
                    String.valueOf(t.value()), false);
        }
        for (Map.Entry<String, Dated<Integer>> e : new TreeMap<>(gradeGraces).entrySet()) {
            Dated<Integer> g = e.getValue();
            sheet = record(sheet, user, GRADE_GRACE + e.getKey(), g.value() == null ? null : BigDecimal.valueOf(g.value()),
                    g.from(), g.value() == null ? "" : String.valueOf(g.value()), true);
        }
        if (proofThreshold != null) {
            sheet = record(sheet, user, PROOF_THRESHOLD, proofThreshold.value(), proofThreshold.from(),
                    text(proofThreshold.value()), false);
        }
        for (Map.Entry<String, Dated<BigDecimal>> e : new TreeMap<>(gradeProofThresholds).entrySet()) {
            Dated<BigDecimal> t = e.getValue();
            sheet = record(sheet, user, GRADE_PROOF_THRESHOLD + e.getKey(), t.value(), t.from(), text(t.value()), false);
        }
        if (noteThreshold != null) {
            sheet = record(sheet, user, NOTE_THRESHOLD, noteThreshold.value(), noteThreshold.from(),
                    text(noteThreshold.value()), false);
        }
        if (reviewThreshold != null) {
            sheet = record(sheet, user, REVIEW_THRESHOLD, reviewThreshold.value(), reviewThreshold.from(),
                    text(reviewThreshold.value()), false);
        }
        for (Map.Entry<String, String> e : permissions.entrySet()) {
            sheet.setPermission(e.getKey(), e.getValue());
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, PERMISSION + e.getKey(), e.getValue()));
        }
        if (changeGrace) {
            sheet.setGraceDays(graceDays);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, GRACE_DAYS, graceDays == null ? "" : graceDays.toString()));
        }
        if (changeEntry) {
            sheet.setEntryDate(entryDate);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, ENTRY_DATE, entryDate == null ? "" : entryDate.toString()));
        }
        if (changePot) {
            sheet.setPot(pot == null ? null : pot.setScale(2));
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, POT, text(sheet.getPot())));
        }
        if (changeParent) {
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            sheet.setParentId(parent);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, PARENT, parent == null ? "" : parent));
            rollup.recordChanges(sheetId, user, before);
        }
        if (fiscalStartMonth != null) {
            sheet.setFiscalStartMonth(fiscalStartMonth);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, FISCAL_START_MONTH, fiscalStartMonth.toString()));
        }
        if (changeAlert) {
            sheet.setAlertThreshold(alertThreshold == null ? null : alertThreshold.setScale(2));
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, ALERT_THRESHOLD, text(sheet.getAlertThreshold())));
        }
        if (changeEscalate) {
            sheet.setEscalateAfter(escalateAfter);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, ESCALATE_AFTER, escalateAfter == null ? "" : escalateAfter.toString()));
        }
        if (changeReviewSla) {
            sheet.setReviewSla(reviewSla);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, REVIEW_SLA, reviewSla == null ? "" : reviewSla.toString()));
        }
        if (changeProofKinds) {
            sheet.setProofKinds(proofKinds);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, PROOF_KINDS, proofKinds == null ? "" : proofKinds));
        }
        if (changeDefaultGrade) {
            sheet.setDefaultGrade(defaultGrade);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, DEFAULT_GRADE, defaultGrade == null ? "" : defaultGrade));
        }
        if (changeScoreMid) {
            sheet.setScoreMid(scoreMid);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, SCORE_MID, scoreMid == null ? "" : scoreMid.toString()));
        }
        if (changeScoreHigh) {
            sheet.setScoreHigh(scoreHigh);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, SCORE_HIGH, scoreHigh == null ? "" : scoreHigh.toString()));
        }
        return sheet;
    }

    /**
     * Record a setting's value from a from-date (null: the sheet's today) and audit it; when it
     * {@code moves} the figures, the figures that move are recorded too.
     */
    private Sheet record(Sheet sheet, String user, String key, BigDecimal value, LocalDate from, String text,
            boolean moves) {
        String sheetId = sheet.getId();
        RollupService.Snapshot before = moves ? rollup.snapshot(sheetId) : null;
        sheet.record(key, from != null ? from : sheet.getToday(), value);
        Sheet saved = sheetStore.saveAndFlush(sheet);
        audit.recordOnCommit(changed(sheetId, user, key, text) + (from != null ? " from=" + from : ""));
        if (moves) {
            rollup.recordChanges(sheetId, user, before);
        }
        return saved;
    }

    private static String changed(String sheetId, String user, String key, String value) {
        return "SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + key + " value=" + value;
    }

    /** A limit with two decimals, or blank for none. */
    public static String text(BigDecimal limit) {
        return limit == null ? "" : limit.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
