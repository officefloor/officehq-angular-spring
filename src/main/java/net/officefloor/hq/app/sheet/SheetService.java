package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet never loaded before is started empty. */
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

    /** The sheet with this id, re-read from the store; started empty if it has never been loaded. */
    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(), LocalDate.now()));
            } catch (DataIntegrityViolationException raced) {
                // Another user started the same sheet at the same moment: theirs stands.
                return sheets.findById(id).orElseThrow(() -> raced);
            }
        });
    }

    /** Set the sheet's today, starting the sheet if it has never been loaded. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        Rollup.Figures before = rollup.of(id);
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        rollup.recordChanges(id, user, before);
        return saved;
    }

    /** Set the sheet's clerk limit (null: no limit), starting the sheet if it has never been loaded. */
    @Transactional
    public Sheet setClerkLimit(String id, String user, BigDecimal limit) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        BigDecimal scaled = limit == null ? null : limit.setScale(2, RoundingMode.UNNECESSARY);
        sheet.setClerkLimit(scaled);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=clerk-limit value=" + (scaled == null ? "" : scaled.toPlainString()));
        return saved;
    }
}
