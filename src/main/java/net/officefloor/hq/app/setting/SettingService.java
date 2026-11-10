package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Rounding;
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
 */
@Service
public class SettingService {

    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String FLOOR = "floor";
    public static final String CAP = "cap";
    public static final String TERMS = "terms";
    /** The key of a grade's own terms: {@code terms-<grade>}. */
    public static final String GRADE_TERMS = "terms-";
    public static final String LATE_PCT = "late-pct";
    public static final String POLICY_DATE = "policy-date";
    public static final String PERIOD_NUMBERING_FROM = "period-numbering-from";

    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final RollupService rollup;
    private final Audit audit;

    public SettingService(SheetService sheets, SheetRepository sheetStore, RollupService rollup, Audit audit) {
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
     * Save the settings given: a clerk limit when {@code changeLimit} (null for no limit), and the
     * floor, the terms and the late percent when they are not null, and a policy date when
     * {@code changePolicy} (null for none), and a period-numbering-from date when
     * {@code changeNumbering} (null for off), and a cap when {@code changeCap} (null for none), and each grade's own terms given in {@code gradeTerms}
     * (by grade, in grade order). Each one saved is recorded, in that order.
     */
    @Transactional
    public Sheet set(String sheetId, String user, boolean changeLimit, BigDecimal limit, BigDecimal floor,
            Integer terms, BigDecimal latePct, boolean changePolicy, LocalDate policyDate,
            boolean changeNumbering, LocalDate numberingFrom, boolean changeCap, BigDecimal cap,
            Map<String, Integer> gradeTerms) {
        Sheet sheet = sheets.load(sheetId, user);
        if (changeLimit) {
            sheet.setClerkLimit(limit);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, CLERK_LIMIT, text(limit)));
        }
        if (floor != null) {
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            sheet.setFloor(floor);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, FLOOR, text(floor)));
            rollup.recordChanges(sheetId, user, before);
        }
        if (terms != null) {
            sheet.setTerms(terms);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, TERMS, String.valueOf(terms)));
        }
        if (latePct != null) {
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            sheet.setLatePct(latePct);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, LATE_PCT, text(latePct)));
            rollup.recordChanges(sheetId, user, before);
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
        if (changeCap) {
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            sheet.setCap(cap);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, CAP, text(cap)));
            rollup.recordChanges(sheetId, user, before);
        }
        for (Map.Entry<String, Integer> e : new TreeMap<>(gradeTerms).entrySet()) {
            sheet.setGradeTerms(e.getKey(), e.getValue());
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, GRADE_TERMS + e.getKey(), String.valueOf(e.getValue())));
        }
        return sheet;
    }

    private static String changed(String sheetId, String user, String key, String value) {
        return "SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + key + " value=" + value;
    }

    /** A limit with two decimals, or blank for none. */
    public static String text(BigDecimal limit) {
        return limit == null ? "" : limit.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
