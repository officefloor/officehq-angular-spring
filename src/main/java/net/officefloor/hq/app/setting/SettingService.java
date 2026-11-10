package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's settings: its clerk limit (none: no limit) and its floor, the least a claim's live due
 * can be, and its terms, the days after its date a claim is due by, and its late percent, what an
 * overdue claim gains of its figure for every full 30 days overdue. Changing the floor or the late
 * percent moves the figures, so the figures that move are recorded. Its policy date (none:
 * the sheet's today) decides how a claim's live figures round (see {@link Rounding}), so changing it
 * records the figures that move too. Its period-numbering-from date (none: off) decides how a claim
 * opened is numbered: on or after it, within its period (see {@link SheetService#nextClaimNumber}).
 * Its cap (none: no cap) is the most a person's due-total can be, so changing it records the
 * figures that move too. A grade may have its own terms; a grade without uses the sheet's terms. A claim's due-by is fixed
 * when it is created, so changing any terms moves no claim's due-by.
 * <p>
 * The settings that carry a figure (the floor, the late percent, the terms, a grade's own terms,
 * the clerk limit, the cap, the proof threshold and the note threshold) are dated: each value is recorded from a from-date (the sheet's
 * today when none is given). A claim's floor, late percent and terms are those in force on its
 * date, as are its proof threshold (see {@link Sheet#proofThresholdOn}) and its note threshold (see {@link Sheet#noteThresholdOn}); the clerk limit and the cap are those in force on the sheet's today (see {@link Sheet}).
 * The policy date and the period-numbering-from date are not dated. Nor is the permission matrix:
 * for each of open, settle, void, reassign, close and reopen, the roles that may take it (blank: the
 * standing rule, see {@link Sheet#permits}), recorded under the key {@code perm-<action>}. Nor are
 * the grace days (none: off): when the sheet's today changes, every open period whose to-date plus
 * the grace days is before the new today is closed (see {@link SheetService#setToday}).
 */
@Service
public class SettingService {

    public static final String CLERK_LIMIT = Sheet.CLERK_LIMIT;
    public static final String FLOOR = Sheet.FLOOR;
    public static final String CAP = Sheet.CAP;
    public static final String TERMS = Sheet.TERMS;
    /** The key of a grade's own terms: {@code terms-<grade>}. */
    public static final String GRADE_TERMS = Sheet.GRADE_TERMS;
    public static final String LATE_PCT = Sheet.LATE_PCT;
    public static final String PROOF_THRESHOLD = Sheet.PROOF_THRESHOLD;
    public static final String NOTE_THRESHOLD = Sheet.NOTE_THRESHOLD;
    public static final String POLICY_DATE = "policy-date";
    public static final String PERIOD_NUMBERING_FROM = "period-numbering-from";
    public static final String GRACE_DAYS = "grace-days";
    /** The key of a permission matrix entry: {@code perm-<action>}. */
    public static final String PERMISSION = "perm-";

    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final RollupService rollup;
    private final Audit audit;
    private final RoleService roles;

    public SettingService(SheetService sheets, SheetRepository sheetStore, RollupService rollup, Audit audit,
            RoleService roles) {
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
     * Save the settings given: a clerk limit when {@code limit} is not null, and the floor, the
     * terms and the late percent when they are not null, and a policy date when {@code changePolicy}
     * (null for none), and a period-numbering-from date when {@code changeNumbering} (null for off),
     * and a cap when {@code cap} is not null, and each grade's own terms given in {@code gradeTerms}
     * (by grade, in grade order), and a proof threshold when {@code proofThreshold} is not null, and a note threshold when
     * {@code noteThreshold} is not null, and each permission matrix entry
     * given in {@code permissions} (by action, in the order given; blank for the standing rule), and the grace days when
     * {@code changeGrace} (null for off). Each figure is recorded from its from-date, keeping the values
     * already recorded (one already recorded from that date is replaced). Each one saved is
     * recorded, in that order.
     */
    @Transactional
    public Sheet set(String sheetId, String user, Dated<BigDecimal> limit, Dated<BigDecimal> floor,
            Dated<Integer> terms, Dated<BigDecimal> latePct, boolean changePolicy, LocalDate policyDate,
            boolean changeNumbering, LocalDate numberingFrom, Dated<BigDecimal> cap,
            Map<String, Dated<Integer>> gradeTerms, Dated<BigDecimal> proofThreshold, Dated<BigDecimal> noteThreshold,
            Map<String, String> permissions,
            boolean changeGrace, Integer graceDays) {
        roles.refuseViewer(sheetId, user);
        Sheet sheet = sheets.load(sheetId, user);
        if (limit != null) {
            sheet = record(sheet, user, CLERK_LIMIT, limit.value(), limit.from(), text(limit.value()), false);
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
        if (proofThreshold != null) {
            sheet = record(sheet, user, PROOF_THRESHOLD, proofThreshold.value(), proofThreshold.from(),
                    text(proofThreshold.value()), false);
        }
        if (noteThreshold != null) {
            sheet = record(sheet, user, NOTE_THRESHOLD, noteThreshold.value(), noteThreshold.from(),
                    text(noteThreshold.value()), false);
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
