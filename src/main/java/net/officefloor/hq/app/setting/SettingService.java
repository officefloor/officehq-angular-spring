package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The sheet's settings: its clerk limit (none: no limit). */
@Service
public class SettingService {

    public static final String CLERK_LIMIT = "clerk-limit";

    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;

    public SettingService(SheetService sheets, SheetRepository sheetStore, Audit audit) {
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    @Transactional
    public Sheet get(String sheetId, String user) {
        return sheets.load(sheetId, user);
    }

    @Transactional
    public Sheet setClerkLimit(String sheetId, String user, BigDecimal limit) {
        Sheet sheet = sheets.load(sheetId, user);
        sheet.setClerkLimit(limit);
        Sheet saved = sheetStore.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + CLERK_LIMIT + " value="
                + text(limit));
        return saved;
    }

    /** A limit with two decimals, or blank for none. */
    public static String text(BigDecimal limit) {
        return limit == null ? "" : limit.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
