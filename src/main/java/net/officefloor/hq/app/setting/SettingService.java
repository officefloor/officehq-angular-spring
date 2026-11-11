package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the sheet's settings: its clerk limit. */
@Service
public class SettingService {

    private final SheetRepository sheets;
    private final Audit audit;

    public SettingService(SheetRepository sheets, Audit audit) {
        this.sheets = sheets;
        this.audit = audit;
    }

    public Sheet get(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(() -> new SheetNotFoundException(sheetId));
    }

    /** Set the clerk limit; null means no limit. Every save is recorded, even of the same value. */
    @Transactional
    public Sheet setClerkLimit(String sheetId, String user, BigDecimal limit) {
        Sheet sheet = get(sheetId);
        sheet.setClerkLimit(limit);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=clerk-limit value="
                + (limit == null ? "" : limit.toPlainString()));
        return saved;
    }
}
