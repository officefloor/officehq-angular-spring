package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
 * overdue claim gains of its figure for every full 30 days overdue. Changing the floor, the terms or
 * the late percent moves the figures, so the figures that move are recorded. Its policy date (none:
 * the sheet's today) decides how a claim's live figures round (see {@link Rounding}), so changing it
 * records the figures that move too.
 */
@Service
public class SettingService {

    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String FLOOR = "floor";
    public static final String TERMS = "terms";
    public static final String LATE_PCT = "late-pct";
    public static final String POLICY_DATE = "policy-date";

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
     * {@code changePolicy} (null for none). Each one saved is recorded, in that order.
     */
    @Transactional
    public Sheet set(String sheetId, String user, boolean changeLimit, BigDecimal limit, BigDecimal floor,
            Integer terms, BigDecimal latePct, boolean changePolicy, LocalDate policyDate) {
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
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            sheet.setTerms(terms);
            sheet = sheetStore.saveAndFlush(sheet);
            audit.recordOnCommit(changed(sheetId, user, TERMS, String.valueOf(terms)));
            rollup.recordChanges(sheetId, user, before);
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
