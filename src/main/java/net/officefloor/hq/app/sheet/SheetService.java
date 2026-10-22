package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import net.officefloor.hq.app.Audit;
import java.time.LocalDate;
import net.officefloor.hq.app.rollup.Rollups;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; an id never loaded before starts a new, empty sheet. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Rollups rollups;
    private final Audit audit;

    public SheetService(SheetRepository sheets, Rollups rollups, Audit audit) {
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** Load a sheet; a new sheet's today starts as the real date. */
    @Transactional
    public synchronized Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> sheets.save(new Sheet(id, Instant.now(), LocalDate.now())));
    }

    /**
     * The sheet's today. Every date-dependent decision and every default date on a sheet uses
     * this, never the real clock.
     */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return load(id).getToday();
    }

    /**
     * Give out the sheet's next claim number, holding the sheet's lock until the caller's
     * transaction ends so two claims opened at once get different numbers.
     */
    @Transactional
    public int nextClaimNumber(String id) {
        load(id);
        return sheets.findLockedById(id).orElseThrow().nextClaimNumber();
    }

    /**
     * Take the sheet's lock, holding it until the caller's transaction ends, so changes that check
     * the sheet's other rows before writing are made one at a time.
     */
    @Transactional
    public Sheet lock(String id) {
        load(id);
        return sheets.findLockedById(id).orElseThrow();
    }

    /** Change the sheet's today, auditing the figures that moves. */
    @Transactional
    public synchronized Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id);
        Rollups.Rollup before = rollups.take(id);
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        rollups.audit(id, user, before);
        return saved;
    }

    /** The sheet's clerk limit; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String id) {
        return load(id).getClerkLimit();
    }

    /** Change the sheet's clerk limit (null: no limit), auditing the change. */
    @Transactional
    public Sheet setClerkLimit(String id, String user, BigDecimal limit) {
        Sheet sheet = lock(id);
        sheet.setClerkLimit(limit == null ? null : limit.setScale(2, RoundingMode.UNNECESSARY));
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=clerk-limit value="
                + (saved.getClerkLimit() == null ? "" : saved.getClerkLimit().toPlainString()));
        return saved;
    }

    /** Change the sheet's floor, auditing the change and the figures it moves. */
    @Transactional
    public Sheet setFloor(String id, String user, BigDecimal floor) {
        Sheet sheet = lock(id);
        Rollups.Rollup before = rollups.take(id);
        sheet.setFloor(floor.setScale(2, RoundingMode.UNNECESSARY));
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=floor value="
                + saved.getFloor().toPlainString());
        rollups.audit(id, user, before);
        return saved;
    }

    /** Change the sheet's terms, in days, auditing the change and the figures it moves (late charges). */
    @Transactional
    public Sheet setTerms(String id, String user, int terms) {
        Sheet sheet = lock(id);
        Rollups.Rollup before = rollups.take(id);
        sheet.setTerms(terms);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=terms value=" + saved.getTerms());
        rollups.audit(id, user, before);
        return saved;
    }

    /** Change the sheet's late percent, auditing the change and the figures it moves. */
    @Transactional
    public Sheet setLatePct(String id, String user, BigDecimal latePct) {
        Sheet sheet = lock(id);
        Rollups.Rollup before = rollups.take(id);
        sheet.setLatePct(latePct.setScale(2, RoundingMode.UNNECESSARY));
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=late-pct value="
                + saved.getLatePct().toPlainString());
        rollups.audit(id, user, before);
        return saved;
    }
}
