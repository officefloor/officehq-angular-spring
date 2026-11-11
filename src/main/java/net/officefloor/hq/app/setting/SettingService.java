package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the sheet's settings: its clerk limit, its floor and its terms. */
@Service
public class SettingService {

    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;

    public SettingService(SheetRepository sheets, Audit audit, RollupService rollup) {
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
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

    /** Set the floor, the least a live due can be. Every save is recorded, then the figures it moved. */
    @Transactional
    public Sheet setFloor(String sheetId, String user, BigDecimal floor) {
        Sheet sheet = get(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setFloor(floor);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=floor value="
                + floor.toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Set the terms, the days after its date a claim is due by. Every save is recorded, even of the same value. */
    @Transactional
    public Sheet setTerms(String sheetId, String user, int terms) {
        Sheet sheet = get(sheetId);
        sheet.setTerms(terms);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=terms value=" + terms);
        return saved;
    }
}
