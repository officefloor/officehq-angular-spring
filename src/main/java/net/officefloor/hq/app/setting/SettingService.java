package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's settings: its clerk limit (none: no limit) and its floor, the least a claim's live due
 * can be. Changing the floor moves the live figures, so the figures that move are recorded.
 */
@Service
public class SettingService {

    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String FLOOR = "floor";

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
     * floor when it is not null. Each one saved is recorded, in that order.
     */
    @Transactional
    public Sheet set(String sheetId, String user, boolean changeLimit, BigDecimal limit, BigDecimal floor) {
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
