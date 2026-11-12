package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet never loaded before is started empty, its today the real date. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Rollup rollup;
    private final Audit audit;

    public SheetService(SheetRepository sheets, Rollup rollup, Audit audit) {
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(), LocalDate.now()));
            } catch (DataIntegrityViolationException e) {
                // Another user started the same sheet at the same moment.
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }

    /** The sheet's today. Every date decision and default date in the sheet goes through this. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseThrow(SheetNotFoundException::new);
    }

    /** Change the sheet's today, recording the figures it moves. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = sheets.findById(id).orElseThrow(SheetNotFoundException::new);
        Rollup.Snapshot before = rollup.take(id);
        sheet.setToday(today);
        rollup.recordChanges(id, user, before);
        return sheet;
    }

    /** Change the sheet's clerk limit: the largest due a clerk may settle; none for no limit. */
    @Transactional
    public Sheet setClerkLimit(String id, String user, BigDecimal limit) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        sheet.setClerkLimit(limit == null ? null : limit.setScale(2));
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=clerk-limit value="
                + (sheet.getClerkLimit() == null ? "" : sheet.getClerkLimit().toPlainString()));
        return sheet;
    }

    /** The sheet's next claim number: 1 for the first, then 2, and so on, never given twice. */
    @Transactional
    public int nextClaimNumber(String id) {
        return sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new).nextClaimNumber();
    }
}
