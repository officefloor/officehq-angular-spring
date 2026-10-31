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

    /** The sheet's today, or null for a sheet never loaded. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElse(null);
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

    /** Set the sheet's floor, starting the sheet if it has never been loaded. */
    @Transactional
    public Sheet setFloor(String id, String user, BigDecimal floor) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        Rollup.Figures before = rollup.of(id);
        BigDecimal scaled = floor.setScale(2, RoundingMode.UNNECESSARY);
        sheet.setFloor(scaled);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=floor value=" + scaled.toPlainString());
        rollup.recordChanges(id, user, before);
        return saved;
    }

    /** Set the sheet's terms in days, starting the sheet if it has never been loaded. */
    @Transactional
    public Sheet setTerms(String id, String user, int terms) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setTerms(terms);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=terms value=" + terms);
        return saved;
    }

    /** Set the sheet's late percent, starting the sheet if it has never been loaded. */
    @Transactional
    public Sheet setLatePct(String id, String user, BigDecimal latePct) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        Rollup.Figures before = rollup.of(id);
        BigDecimal scaled = latePct.setScale(2, RoundingMode.UNNECESSARY);
        sheet.setLatePct(scaled);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=late-pct value=" + scaled.toPlainString());
        rollup.recordChanges(id, user, before);
        return saved;
    }

    /** Set the sheet's policy date (null: the sheet's today), starting the sheet if it has never been loaded. */
    @Transactional
    public Sheet setPolicyDate(String id, String user, LocalDate policyDate) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        Rollup.Figures before = rollup.of(id);
        sheet.setPolicyDate(policyDate);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=policy-date value=" + (policyDate == null ? "" : policyDate));
        rollup.recordChanges(id, user, before);
        return saved;
    }

    /** Set the sheet's period numbering from (null: off), starting the sheet if it has never been loaded. */
    @Transactional
    public Sheet setPeriodNumberingFrom(String id, String user, LocalDate from) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setPeriodNumberingFrom(from);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=period-numbering-from value=" + (from == null ? "" : from));
        return saved;
    }
}
